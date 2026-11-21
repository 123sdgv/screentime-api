// The Android archive preview shares a Vercel project with a legacy API whose reads can mutate Redis.
// Keep the legacy tree unreachable in this preview, before filesystem route matching.
export default {
  async rewrites() {
    const preview = process.env.VERCEL_ENV === "preview" && process.env.VERCEL_GIT_COMMIT_REF === "mcp-annual-private";
    return {beforeFiles:preview ? [
      {source:"/api/:transport",destination:"/legacy-unavailable"},
      {source:"/api/screentime/:path*",destination:"/legacy-unavailable"},
      {source:"/screentime/:path*",destination:"/legacy-unavailable"}
    ] : [],afterFiles:[],fallback:[]};
  }
};
