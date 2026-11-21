import { Redis } from "@upstash/redis";
import { createHash, randomBytes, timingSafeEqual } from "node:crypto";

export const digest = s => createHash("sha256").update(s).digest("hex");
export const randomToken = () => randomBytes(32).toString("base64url");
export function matchesDigest(value, expected) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(expected) &&
    timingSafeEqual(Buffer.from(digest(value),"hex"),Buffer.from(expected,"hex"));
}
export class Problem extends Error {
  constructor(status,message) {super(message); this.status=status;}
}

const commitDay = `
if redis.call('GET', KEYS[3]) ~= ARGV[3] then return -3 end
local prior = redis.call('GET', KEYS[1])
if prior then
  local r = cjson.decode(prior)
  if r.revision > tonumber(ARGV[1]) then return -1 end
  if r.revision == tonumber(ARGV[1]) then
    if prior == ARGV[2] then return 0 else return -2 end
  end
end
redis.call('SET', KEYS[1], ARGV[2])
redis.call('SET', KEYS[2], ARGV[4])
return 1`;
const claimDevice = `
local device = redis.call('GET',KEYS[2])
if device and device ~= ARGV[1] then return -1 end
if redis.call('DEL',KEYS[1]) ~= 1 then return 0 end
redis.call('SET',KEYS[2],ARGV[1])
redis.call('SET',KEYS[3],ARGV[2],'EX',tonumber(ARGV[3]))
return 1`;
const count = "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]) end; return n";
export class ArchiveStore {
  constructor(redis, reader, prefix) {this.redis=redis;this.reader=reader;this.prefix=prefix;}
  k(key) {return this.prefix+":"+key;}
  async get(key) {const s=await this.redis.get(this.k(key));return s==null?null:JSON.parse(s);}
  async put(key,value,ttl) {await this.redis.set(this.k(key),JSON.stringify(value),{ex:ttl});}
  async take(key) {return await this.redis.del(this.k(key))===1;}
  async limit(key,n,seconds=60) {
    if (await this.redis.eval(count,[this.k("rate:"+key)],[seconds])>n) throw new Problem(429,"Too many requests");
  }
  async pair(code,device,credential,ttl) {
    const result=await this.redis.eval(claimDevice,[this.k("invite:"+digest(code)),this.k("device"),this.k("writer")],
      [device,digest(credential),ttl]);
    if (result!==1) throw new Problem(result===-1?409:401,result===-1?"Another device is already paired":"Invalid or expired invitation");
  }
  async writer(value) {
    if (!value) return false;
    const stored=await this.redis.get(this.k("writer")); return matchesDigest(value,stored);
  }
  async writeDay(day) {
    const now=new Date().toISOString();
    const result=await this.redis.eval(commitDay,[this.k("day:"+day.date),this.k("last-sync"),this.k("device")],
      [day.revision,JSON.stringify(day),day.device_id,JSON.stringify({at:now,zone:day.device_timezone})]);
    if(result<0) throw new Problem(result===-3?403:409,result===-3?"Device mismatch":"Stale or conflicting revision");
    return result===1?"stored":"unchanged";
  }
  async readDays(dates) {
    for(let attempt=0;attempt<2;attempt++) {
      const before=await this.reader.get(this.k("last-sync"));
      const records=[];
      for(let i=0;i<dates.length;i+=12) {
        const chunk=dates.slice(i,i+12).map(d=>this.k("day:"+d));
        const values=await this.reader.mget(...chunk);
        records.push(...values.map(v=>v==null?null:JSON.parse(v)));
      }
      const lastSync=await this.reader.get(this.k("last-sync"));
      if(before===lastSync) {
        const meta=lastSync && JSON.parse(lastSync);
        return {records,lastSync:meta?.at??null,today:new Date().toLocaleDateString("sv-SE",{timeZone:meta?.zone || "UTC"})};
      }
    }
    throw new Problem(503,"Sync in progress; retry the query");
  }
}
export function settings(env=process.env) {
  const origin=env.SCREENTIME_ORIGIN, ownerHash=env.SCREENTIME_OWNER_SHA256;
  if (!origin || !/^https:\/\//.test(origin) || new URL(origin).origin!==origin ||
      !/^[0-9a-f]{64}$/.test(ownerHash || "")) throw new Problem(503,"Private archive not configured");
  const url=env.ANNUAL_KV_REST_API_URL, token=env.ANNUAL_KV_REST_API_TOKEN, read=env.ANNUAL_KV_REST_API_READ_ONLY_TOKEN;
  if(!url || !token || !read) throw new Problem(503,"Private archive storage unavailable");
  const client=t=>new Redis({url,token:t,automaticDeserialization:false});
  return {origin,ownerHash,store:new ArchiveStore(client(token),client(read),"st:annual:v1:"+digest(origin).slice(0,24))};
}
