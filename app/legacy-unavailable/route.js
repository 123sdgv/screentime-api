function unavailable() { return new Response(null, { status: 404, headers: {"Cache-Control":"no-store"} }); }
export {unavailable as GET,unavailable as POST};
