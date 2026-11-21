import { test } from "node:test";
import assert from "node:assert/strict";
import { promisify } from "node:util";
import { execFile } from "node:child_process";
import { randomUUID, createHash } from "node:crypto";
import { Client } from "@modelcontextprotocol/sdk/client/index.js";
import { StreamableHTTPClientTransport } from "@modelcontextprotocol/sdk/client/streamableHttp.js";
import { ArchiveStore, randomToken, digest } from "../lib/annual/store.mjs";
import { handle } from "../lib/annual/http.mjs";
import { analyze, daysBetween } from "../lib/annual/stats.mjs";

const run=promisify(execFile);
class LocalRedis {
  async cmd(...args) {
    const {stdout}=await run(process.env.REDIS_TEST_CLI || "redis-cli",["--json","-h","127.0.0.1","-p",process.env.REDIS_TEST_PORT || "6379",...args.map(String)]);
    return JSON.parse(stdout);
  }
  get(k){return this.cmd("GET",k);}
  set(k,v,{ex}){return this.cmd("SET",k,v,"EX",ex);}
  del(k){return this.cmd("DEL",k);}
  mget(...keys){return this.cmd("MGET",...keys);}
  eval(lua,keys,args){return this.cmd("EVAL",lua,keys.length,...keys,...args);}
}
const device="4b954a2d-851b-4ddb-a7ca-508aad1404b4";
const makeDay=(date,coverage="partial")=>({schema:1,device_id:device,date,coverage,revision:1,device_timezone:"Asia/Shanghai",apps:[
  {package_name:"example.test",name:"Synthetic",system:false,usage_ms:60000,foreground_entries:null,source:"system",timezone_id:"Asia/Shanghai"}
]});
test("calendar and missing metrics, leap year, comparison on common dates",()=>{
  assert.equal(daysBetween("2024-01-01","2024-12-31").length,366);
  assert.throws(()=>daysBetween("2024-01-01","2025-02-01"));
  const ds=daysBetween("2024-02-28","2024-03-01");
  const docs=[makeDay(ds[0]),{...makeDay(ds[1],"complete"),apps:[]},null];
  const heat=analyze("get_app_heatmap",{package_name:"example.test",metric:"foreground_entries"},ds,docs,null,"2024-03-01");
  assert.deepEqual(heat.days.map(d=>d.value),[null,0,null]);
  const cmp=analyze("compare_apps",{packages:["example.test","missing"],metric:"usage_ms"},ds,docs,null,"2024-03-01");
  assert.equal(cmp.common_known_days,1);assert.equal(cmp.apps[0].usage_ms,0);
});
test("service denies access without private configuration",async()=>{
  const req=new Request("https://archive.invalid/api/v1/mcp",{method:"POST"});
  const {settings}=await import("../lib/annual/store.mjs");
  assert.equal((await handle(req,()=>settings({}))).status,503);
});
test("private device pairing, Redis revisions, OAuth PKCE, official MCP client and revocation",async()=>{
  const redis=new LocalRedis();
  const store=new ArchiveStore(redis,redis,"test:"+randomUUID());
  const origin="https://archive.test", resource=origin+"/api/v1/mcp", owner=randomToken();
  const config=()=>({origin,store,ownerHash:digest(owner)});
  const req=(path,method="GET",body,extra={})=>handle(new Request(origin+path,{method,headers:extra,body}),config);
  const post=(path,b,token)=>req(path,"POST",JSON.stringify(b),{"Content-Type":"application/json",...(token?{Authorization:"Bearer "+token}:{})});
  const form=(path,d,extra={})=>req(path,"POST",new URLSearchParams(d),{"Content-Type":"application/x-www-form-urlencoded",Origin:origin,...extra});
  assert.equal((await post("/api/v1/mcp",{})).status,401);
  assert.equal((await req("/.well-known/oauth-protected-resource/api/v1/mcp")).status,200);
  const invite=await form("/api/v1/manage",{owner,action:"pair"}); assert.equal(invite.status,200);
  const code=/<code>([\w-]+)<\/code>/.exec(await invite.text())[1];
  const pair=await post("/api/v1/pair",{code,device_id:device});
  const writer=(await pair.json()).access_token;assert.ok(writer);
  assert.equal((await post("/api/v1/pair",{code,device_id:device})).status,401);
  assert.equal((await post("/api/v1/mcp",{},writer)).status,401);
  const day=makeDay("2024-02-29");
  assert.equal((await post("/api/v1/sync/daily",day)).status,401);
  assert.equal((await (await post("/api/v1/sync/daily",day,writer)).json()).status,"stored");
  assert.equal((await (await post("/api/v1/sync/daily",day,writer)).json()).status,"unchanged");
  assert.equal((await post("/api/v1/sync/daily",{...day,apps:[]},writer)).status,409);
  assert.equal((await post("/api/v1/sync/daily",{...day,revision:2},writer)).status,200);
  assert.equal((await post("/api/v1/sync/daily",day,writer)).status,409);
  assert.equal((await post("/api/v1/sync/daily",{...day,date:"2024-02-30"},writer)).status,400);
  const redirect="https://chatgpt.com/connector_platform_oauth_redirect";
  assert.equal((await post("/api/v1/register",{redirect_uris:["https://untrusted.invalid/cb"]})).status,400);
  const client=await (await post("/api/v1/register",{redirect_uris:[redirect],token_endpoint_auth_method:"none"})).json();
  assert.ok(client.client_id);
  const verifier=randomToken(), challenge=createHash("sha256").update(verifier).digest("base64url");
  const consentPath="/api/v1/authorize?"+new URLSearchParams({resource,client_id:client.client_id,redirect_uri:redirect,state:"test-state",
    response_type:"code",scope:"archive.read",code_challenge_method:"S256",code_challenge:challenge});
  assert.equal((await form(consentPath,{owner:"incorrect"})).status,403);
  assert.equal((await form(consentPath,{owner},{Origin:"https://other.test"})).status,403);
  const grant=await form(consentPath,{owner});assert.equal(grant.status,303);
  const cb=new URL(grant.headers.get("location"));assert.equal(cb.searchParams.get("iss"),origin);assert.equal(cb.searchParams.get("state"),"test-state");
  const exchange={grant_type:"authorization_code",client_id:client.client_id,redirect_uri:redirect,resource,code_verifier:verifier,code:cb.searchParams.get("code")};
  assert.equal((await form("/api/v1/token",{...exchange,resource:origin+"/other"})).status,400);
  const response=await form("/api/v1/token",exchange);
  const t=await response.json();assert.ok(t.access_token,JSON.stringify(t));assert.equal(t.scope,"archive.read");
  assert.equal((await form("/api/v1/token",exchange)).status,400);
  assert.equal((await post("/api/v1/sync/daily",day,t.access_token)).status,401);
  const sdk=new Client({name:"test-reader",version:"1"});
  const transport=new StreamableHTTPClientTransport(new URL(resource),{requestInit:{headers:{Authorization:"Bearer "+t.access_token}},
    fetch:(url,init)=>handle(new Request(url,init),config)});
  await sdk.connect(transport);
  const list=await sdk.listTools();
  assert.equal(list.tools.length,5);assert.ok(list.tools.every(t=>t.annotations.readOnlyHint && !t.annotations.destructiveHint));
  const before=(await store.readDays([day.date])).lastSync;
  for(const [name,args] of [
    ["get_yearly_overview",{year:2024}], ["get_app_heatmap",{year:2024,package_name:"example.test"}],
    ["get_app_usage",{package_name:"example.test",start_date:day.date,end_date:day.date}],
    ["compare_apps",{packages:["example.test","example.second"],start_date:day.date,end_date:day.date}],
    ["get_data_coverage",{start_date:day.date,end_date:day.date}]
  ]){
    const answer=await sdk.callTool({name,arguments:args});assert.ok(!answer.isError,JSON.stringify(answer));
    const value=JSON.parse(answer.content[0].text);assert.equal(value.last_sync_at,before);assert.equal(value.coverage.partial,1);
    if(name==="get_yearly_overview"){assert.equal(value.usage_ms,60000);assert.equal(value.foreground_entries,null);}
  }
  assert.equal((await store.readDays([day.date])).lastSync,before);
  await sdk.close();
  await form("/api/v1/manage",{owner,action:"revoke"});
  assert.equal((await post("/api/v1/mcp",{},t.access_token)).status,401);
  await form("/api/v1/manage",{owner,action:"unpair"});
  assert.equal((await post("/api/v1/sync/daily",day,writer)).status,401);
});
