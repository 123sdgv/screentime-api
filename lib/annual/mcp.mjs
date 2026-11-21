import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { analyze, date, daysBetween, metrics, pkg, year } from "./stats.mjs";

const range = {start_date:date,end_date:date};
const specs = [
  ["get_yearly_overview","Selected year's observed usage, distinct active dates, coverage and app ranking.",
    {year,sort:metrics.default("usage_ms"),include_system:z.boolean().default(false),limit:z.number().int().min(1).max(100).default(15)}],
  ["get_app_heatmap","Every local date in the year with a nullable metric and coverage.",
    {package_name:pkg,year,metric:z.enum(["usage_ms","foreground_entries"]).default("foreground_entries")}],
  ["get_app_usage","Observed totals and daily source for one package; up to 366 days.",{package_name:pkg,...range}],
  ["compare_apps","Compare two to eight apps over dates where the requested metric is known for all.",{packages:z.array(pkg).min(2).max(8).refine(a=>new Set(a).size===a.length),...range,metric:metrics.default("usage_ms")}],
  ["get_data_coverage","Coverage by date, including unknown dates and last sync time.",range]
];
export async function serveMcp(req, body, store, today) {
  const server = new McpServer({name:"ScreenTime annual archive",version:"1.0.0"},{instructions:
    "Use only observed usage. Missing is unknown, foreground entries are estimates. Report coverage and last sync with conclusions."});
  for (const [name,description,inputSchema] of specs) {
    server.registerTool(name,{description,inputSchema,annotations:{readOnlyHint:true,destructiveHint:false,idempotentHint:true,openWorldHint:false}},
      async input=>{
        const start=input.year ? input.year+"-01-01" : input.start_date;
        const end=input.year ? input.year+"-12-31" : input.end_date;
        const dates=daysBetween(start,end);
        const snapshot=await store.readDays(dates);
        const result=analyze(name,input,dates,snapshot.records,snapshot.lastSync,snapshot.today || today);
        return {content:[{type:"text",text:JSON.stringify(result)}]};
      });
  }
  const transport = new WebStandardStreamableHTTPServerTransport({sessionIdGenerator:undefined,enableJsonResponse:true});
  await server.connect(transport);
  try {return await transport.handleRequest(req,{parsedBody:body});}
  finally {await server.close();}
}
