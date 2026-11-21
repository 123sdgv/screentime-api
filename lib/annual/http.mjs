import OAuth from "@node-oauth/oauth2-server";
import { z } from "zod";
import { digest, matchesDigest, Problem, randomToken, settings } from "./store.mjs";
import { daySchema } from "./stats.mjs";
import { serveMcp } from "./mcp.mjs";

const scope="archive.read", yearSeconds=365*86400;
const headers={"Cache-Control":"no-store","Referrer-Policy":"no-referrer","X-Content-Type-Options":"nosniff"};
const json=(value,status=200,extra={})=>Response.json(value,{status,headers:{...headers,...extra}});
const html = body => new Response('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>我的数字生活 · 授权</title><main><h1>我的数字生活</h1>'+body+'</main></html>',
  {headers:{...headers,"Content-Type":"text/html; charset=utf-8","Content-Security-Policy":"default-src 'none'; form-action 'self' https://chatgpt.com; frame-ancestors 'none'; base-uri 'none'"}});
const ownerInput='<label>档案主人的管理口令 <input type="password" name="owner" autocomplete="current-password" required maxlength="200"></label>';
const callback=s=>typeof s==="string" && /^https:\/\/chatgpt\.com\/(connector_platform_oauth_redirect|connector\/oauth\/[a-zA-Z0-9_-]{1,160})$/.test(s);
const bearer=req=>/^Bearer ([-_a-zA-Z0-9]{30,180})$/i.exec(req.headers.get("authorization") || "")?.[1];
async function bodyText(req,max) {
  if(Number(req.headers.get("content-length"))>max) throw new Problem(413,"Body too large");
  const reader=req.body?.getReader(), decoder=new TextDecoder(); let text="",size=0;
  if(reader) for(;;) {
    const {done,value}=await reader.read(); if(done)break;
    size+=value.length; if(size>max){await reader.cancel();throw new Problem(413,"Body too large");}
    text+=decoder.decode(value,{stream:true});
  }
  return text+decoder.decode();
}
async function read(req,type,max=8192) {
  if(req.headers.get("content-type")?.split(";")[0].trim()!==type) throw new Problem(415,"Unsupported content type");
  const text=await bodyText(req,max);
  if(type==="application/json") {try{return JSON.parse(text);}catch{throw new Problem(400,"Invalid JSON");}}
  return params(new URLSearchParams(text));
}
function params(p) {
  const entries=[...p];
  if(new Set(entries.map(([k])=>k)).size!==entries.length) throw new Problem(400,"Duplicate parameter");
  return Object.fromEntries(entries);
}
function method(req,expected){if(req.method!==expected) throw new Problem(405,"Method not allowed");}
function consent(req,origin){if(req.headers.get("origin")!==origin)throw new Problem(403,"Cross-origin consent is disabled");}
async function owner(store,value,hash) {
  await store.limit("owner-attempts",16,60);
  if(!matchesDigest(value,hash)) throw new Problem(403,"Owner authorization required");
}
async function epoch(store,ownerHash){return digest(ownerHash+":"+(await store.get("read-generation") || "initial"));}
function oauthModel(store,resource,ownerHash,tokenEndpoint) {
  return {
    async getClient(id,secret) {
      const c=await store.get("client:"+digest(id));
      if(!c || (tokenEndpoint && c.confidential && !matchesDigest(secret,c.secretHash)))return null;
      return {id,redirectUris:c.redirectUris,grants:["authorization_code"]};
    },
    validateScope:(_u,_c,scopes)=>scopes?.length===1 && scopes[0]===scope ? scopes : false,
    async saveAuthorizationCode(code,client,user){
      const {authorizationCode,...data}=code;
      await store.put("code:"+digest(authorizationCode),{...data,client,user,resource},300);
      return {...code,client,user};
    },
    async getAuthorizationCode(raw) {
      const c=await store.get("code:"+digest(raw));
      if(!c || c.resource!==resource || c.user.generation!==await epoch(store,ownerHash))return null;
      return {...c,authorizationCode:raw,expiresAt:new Date(c.expiresAt)};
    },
    revokeAuthorizationCode:c=>store.take("code:"+digest(c.authorizationCode)),
    async saveToken(t,client,user) {
      const {accessToken,accessTokenExpiresAt}=t;
      await store.put("read:"+digest(accessToken),{resource,scope,generation:user.generation},3600);
      return {accessToken,accessTokenExpiresAt,scope:[scope],client,user};
    }
  };
}
export async function handle(req,config=settings) {
  try {
    const {origin,store,ownerHash}=config();
    const u=new URL(req.url), path=u.pathname;
    if(u.origin!==origin)throw new Problem(404,"Unknown archive origin");
    const resource=origin+"/api/v1/mcp", meta=origin+"/.well-known/oauth-protected-resource/api/v1/mcp";
    if(path==="/.well-known/oauth-protected-resource/api/v1/mcp"){
      method(req,"GET");return json({resource,authorization_servers:[origin],scopes_supported:[scope],bearer_methods_supported:["header"]});
    }
    if(path==="/.well-known/oauth-authorization-server"){
      method(req,"GET");return json({issuer:origin,authorization_endpoint:origin+"/api/v1/authorize",token_endpoint:origin+"/api/v1/token",
        registration_endpoint:origin+"/api/v1/register",scopes_supported:[scope],code_challenge_methods_supported:["S256"],
        response_types_supported:["code"],grant_types_supported:["authorization_code"],token_endpoint_auth_methods_supported:["none","client_secret_post","client_secret_basic"],
        authorization_response_iss_parameter_supported:true});
    }
    if(path==="/api/v1/register"){
      method(req,"POST");const d=await read(req,"application/json");
      if(!Array.isArray(d.redirect_uris)||d.redirect_uris.length!==1||!callback(d.redirect_uris[0]))throw new Problem(400,"Unsupported redirect URI");
      const auth=d.token_endpoint_auth_method??"client_secret_basic";
      if(!["none","client_secret_post","client_secret_basic"].includes(auth))throw new Problem(400,"Unsupported client authentication");
      await store.limit("register",20,3600);
      const id=randomToken(), secret=auth==="none"?null:randomToken();
      await store.put("client:"+digest(id),{redirectUris:d.redirect_uris,confidential:!!secret,secretHash:secret?digest(secret):null},yearSeconds);
      return json({client_id:id,...(secret?{client_secret:secret,client_secret_expires_at:Math.floor(Date.now()/1000)+yearSeconds}:{}),
        redirect_uris:d.redirect_uris,scope,token_endpoint_auth_method:auth,grant_types:["authorization_code"],response_types:["code"]},201);
    }
    if(path==="/api/v1/authorize" || path==="/api/v1/token"){
      const issue=path.endsWith("/token");
      const model=oauthModel(store,resource,ownerHash,issue);
      const server=new OAuth({model,accessTokenLifetime:3600,authorizationCodeLifetime:300,requirePKCE:true,enablePlainPKCE:false});
      const query=params(u.searchParams), response=new OAuth.Response();
      let values={}, user;
      if(!issue){
        if(query.resource!==resource || query.scope!==scope || !callback(query.redirect_uri) || query.response_type!=="code" ||
          query.code_challenge_method!=="S256" || !/^[A-Za-z0-9_-]{43}$/.test(query.code_challenge||"") ||
          !query.state || query.state.length>512 || !query.client_id)throw new Problem(400,"Invalid authorization request");
        const client=await model.getClient(query.client_id,null);
        if(!client?.redirectUris.includes(query.redirect_uri))throw new Problem(400,"Unknown registered client");
        if(req.method==="GET")return html('<h2>允许 ChatGPT 只读查询？</h2><p>可按应用和日期读取已同步的时长、估算进入次数及覆盖情况。授权持续一小时，可在管理页撤销。</p><form method="post">'+ownerInput+'<button>允许本次只读访问</button></form>');
        method(req,"POST");consent(req,origin);values=await read(req,"application/x-www-form-urlencoded");
        await owner(store,values.owner,ownerHash);values={};
        user={id:"owner",generation:await epoch(store,ownerHash)};
      } else {
        method(req,"POST");values=await read(req,"application/x-www-form-urlencoded");
        if(values.resource!==resource || values.grant_type!=="authorization_code" || !callback(values.redirect_uri))throw new Problem(400,"Invalid token request");
        await store.limit("token",120);
      }
      const request=new OAuth.Request({method:issue?"POST":"GET",query,body:values,headers:{...Object.fromEntries(req.headers),
        "content-length":req.headers.get("content-length") || "1"}});
      try {
        if(issue){await server.token(request,response);return json(response.body);}
        await server.authorize(request,response,{authenticateHandler:{handle:()=>user}});
        const redirect=new URL(response.get("location"));redirect.searchParams.set("iss",origin);
        return new Response(null,{status:303,headers:{...headers,Location:redirect.href}});
      } catch(e){return json({error:e.name==="invalid_client"?"invalid_client":issue?"invalid_grant":"invalid_request"},400);}
    }
    if(path==="/api/v1/manage") {
      if(req.method==="GET")return html('<h2>私有档案管理</h2><form method="post">'+ownerInput+'<p><button name="action" value="pair">生成五分钟手机配对码</button></p><p><button name="action" value="revoke">撤销所有 ChatGPT 读取授权</button></p><p><button name="action" value="unpair">撤销手机上传凭据</button></p></form>');
      method(req,"POST");consent(req,origin);const d=await read(req,"application/x-www-form-urlencoded");await owner(store,d.owner,ownerHash);
      if(d.action==="pair"){const code=randomToken();await store.put("invite:"+digest(code),1,300);return html('<p>五分钟内将一次性配对码输入你自己的手机或本地导入工具。</p><code>'+code+'</code>');}
      if(d.action==="revoke"){await store.put("read-generation",randomToken(),yearSeconds);return html("<p>已撤销现有读取授权。</p>");}
      if(d.action==="unpair"){await store.take("writer");return html("<p>手机上传凭据已撤销，已有档案保留。</p>");}
      throw new Problem(400,"Invalid action");
    }
    if(path==="/api/v1/pair"){
      method(req,"POST");const d=z.object({code:z.string().regex(/^[\w-]{43}$/),device_id:z.string().uuid()}).strict().parse(await read(req,"application/json"));
      await store.limit("pair",30);
      const token=randomToken();await store.pair(d.code,d.device_id,token,30*86400);
      return json({access_token:token,scope:"archive.upload",expires_in:30*86400});
    }
    if(path==="/api/v1/sync/daily" || path==="/api/v1/sync/status"){
      if(!await store.writer(bearer(req)))throw new Problem(401,"Device authorization required");
      await store.limit("upload",240);
      if(path.endsWith("/status")){method(req,"GET");return json({last_sync_at:(await store.readDays([])).lastSync});}
      method(req,"POST");const d=daySchema.parse(await read(req,"application/json",240*1024));
      if(d.date>new Date(Date.now()+14*3600000).toISOString().slice(0,10))throw new Problem(400,"Future date");
      d.apps.sort((a,b)=>a.package_name.localeCompare(b.package_name));
      return json({status:await store.writeDay(d),date:d.date,revision:d.revision});
    }
    if(path===new URL(resource).pathname){
      const token=bearer(req), grant=token && await store.get("read:"+digest(token));
      if(!grant || grant.scope!==scope || grant.resource!==resource || grant.generation!==await epoch(store,ownerHash))
        return json({error:"unauthorized"},401,{"WWW-Authenticate":'Bearer resource_metadata="'+meta+'", scope="'+scope+'"'});
      method(req,"POST");
      if(req.headers.has("origin") && req.headers.get("origin")!==origin)throw new Problem(403,"Untrusted Origin");
      await store.limit("read",90);const b=await read(req,"application/json",16000);
      if(!b || typeof b!=="object" || Array.isArray(b))throw new Problem(400,"Invalid MCP message");
      const answer=await serveMcp(req,b,store,new Date().toISOString().slice(0,10));
      for(const [key,value] of Object.entries(headers))answer.headers.set(key,value);
      return answer;
    }
    throw new Problem(404,"Unknown endpoint");
  }catch(e){
    if(e instanceof z.ZodError)return json({error:"Invalid archive fields"},400);
    if(e instanceof Problem)return json({error:e.message},e.status);
    // Report only fixed categories. Provider messages can contain private URLs or credentials.
    const detail = [e?.message, e?.cause?.message, e?.cause?.code].join(" ");
    const category = [
      [/WRONGPASS|unauth|invalid token|401|NOPERM|NOAUTH|forbidden|403/i, "storage-credentials"],
      [/quota|limit exceeded|suspended|disabled|archived|inactive/i, "storage-account"],
      [/ENOTFOUND|EAI_AGAIN|getaddrinfo/i, "storage-dns"],
      [/fetch failed|timeout|ECONN|network/i, "storage-network"],
      [/invalid url|invalid input url/i, "storage-url"],
      [/not found|404/i, "storage-endpoint"],
      [/eval|script/i, "storage-command"]
    ].find(([pattern]) => pattern.test(detail))?.[1] ?? "unexpected";
    console.error("Annual archive request failed", category);
    return json({error:"Private archive temporarily unavailable", diagnostic:category},503);
  }
}
