/**
 * Grabbly on Cloudflare.
 *
 * - The website (public/) is served as static assets from Cloudflare's network, so pages load
 *   instantly even while the download server is asleep.
 * - /api/* goes to a Cloudflare Container running the Python download server from ./Dockerfile
 *   (FastAPI + yt-dlp + ffmpeg). It sleeps when idle and starts again on the next request.
 * - Google AdSense IDs are added to the home page here, and /ads.txt is generated here.
 */
import { Container, getContainer } from "@cloudflare/containers";

interface Env {
	ASSETS: Fetcher;
	GRABBLY: DurableObjectNamespace<GrabblyServer>;
	ADSENSE_CLIENT?: string; // ca-pub-1234567890123456
	ADSENSE_SLOT?: string; // numeric display ad unit ID
	GRABBLY_AUDIO?: string; // "1" (default) offers MP3 downloads
	GRABBLY_SECRET?: string; // optional secret for signing thumbnail links
}

/** The Python download server (the Dockerfile in the repository root). */
export class GrabblyServer extends Container<Env> {
	defaultPort = 8000; // uvicorn's port in the Dockerfile
	sleepAfter = "30m"; // stop after 30 idle minutes; the next request starts it again

	constructor(ctx: DurableObjectState<{}>, env: Env) {
		super(ctx, env);
		this.envVars = {
			// The Worker below sets X-Forwarded-For to the visitor's real IP for rate limiting
			GRABBLY_TRUST_PROXY: "1",
			GRABBLY_PROXY_HOPS: "1",
			GRABBLY_AUDIO: env.GRABBLY_AUDIO || "1",
			...(env.GRABBLY_SECRET ? { GRABBLY_SECRET: env.GRABBLY_SECRET } : {}),
		};
	}
}

const PUBLISHER_ID = /^ca-pub-\d{10,20}$/;
const AD_UNIT_ID = /^\d{5,20}$/;
const GOOGLE_ADS_TXT_ID = "f08c47fec0942fa0"; // Google's fixed certification ID for ads.txt

type Ads = { client: string; slot: string } | null;

function adsConfig(env: Env): Ads {
	const client = (env.ADSENSE_CLIENT || "").trim();
	const slot = (env.ADSENSE_SLOT || "").trim();
	return PUBLISHER_ID.test(client) && AD_UNIT_ID.test(slot) ? { client, slot } : null;
}

export default {
	async fetch(request, env): Promise<Response> {
		const url = new URL(request.url);
		const ads = adsConfig(env);
		let response: Response;

		if (url.pathname === "/api/health") {
			// Answered here so simply opening the page doesn't wake the download server
			response = Response.json({ ok: true, audio: env.GRABBLY_AUDIO !== "0" });
		} else if (url.pathname.startsWith("/api/")) {
			response = await toDownloadServer(request, env);
		} else if (url.pathname === "/ads.txt") {
			response = ads
				? new Response(`google.com, ${ads.client.slice("ca-".length)}, DIRECT, ${GOOGLE_ADS_TXT_ID}\n`, {
						headers: { "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "public, max-age=86400" },
					})
				: new Response("Not found.\n", { status: 404, headers: { "Content-Type": "text/plain; charset=utf-8" } });
		} else {
			response = await env.ASSETS.fetch(request);
			if (ads && (response.headers.get("Content-Type") || "").includes("text/html")) {
				response = addAdSense(response, ads);
			}
		}
		return withSecurityHeaders(response);
	},
} satisfies ExportedHandler<Env>;

async function toDownloadServer(request: Request, env: Env): Promise<Response> {
	// The server limits requests per visitor using X-Forwarded-For. Replace whatever the visitor
	// sent with the address Cloudflare saw, so the limit can't be dodged with a fake header.
	const headers = new Headers(request.headers);
	headers.set("X-Forwarded-For", request.headers.get("CF-Connecting-IP") || "unknown");
	// One shared instance keeps the in-memory rate limits consistent.
	const server = getContainer(env.GRABBLY, "main");
	try {
		return await server.fetch(new Request(request, { headers }));
	} catch (err) {
		console.error("download server unavailable", err);
		return Response.json({ error: "Grabbly is starting up. Please try again in a few seconds." }, { status: 503 });
	}
}

function addAdSense(response: Response, ads: NonNullable<Ads>): Response {
	// IDs are validated above, so they are safe to put into HTML
	return new HTMLRewriter()
		.on('meta[name="grabbly-ads-client"]', {
			element(el) {
				el.setAttribute("content", ads.client);
				el.after(`<meta name="google-adsense-account" content="${ads.client}">`, { html: true });
			},
		})
		.on('meta[name="grabbly-ads-slot"]', {
			element(el) {
				el.setAttribute("content", ads.slot);
			},
		})
		.transform(response);
}

function withSecurityHeaders(response: Response): Response {
	const res = new Response(response.body, response);
	res.headers.set("X-Content-Type-Options", "nosniff");
	res.headers.set("Referrer-Policy", "strict-origin-when-cross-origin");
	res.headers.set("X-Frame-Options", "DENY");
	return res;
}
