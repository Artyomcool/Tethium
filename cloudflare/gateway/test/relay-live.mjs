import { createCipheriv, createDecipheriv, createHmac, randomBytes } from "node:crypto";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import WebSocket from "ws";

const config = JSON.parse((await readFile(join(process.env.LOCALAPPDATA, "Tethium", "mobile", "relay.json"), "utf8")).replace(/^\uFEFF/, ""));
const secret = randomBytes(32);
const derive = label => createHmac("sha256", secret).update(label).digest();
const b64 = bytes => bytes.toString("base64url");
const room = b64(derive("tethium-relay-room-v1"));
const auth = b64(derive("tethium-relay-auth-v1"));
const key = derive("tethium-relay-aes-v1");
const endpoint = `${config.url}/v1/connect/${room}`;
const ticket = role => createHmac("sha256", Buffer.from(config.admission)).update(`${room}\n${role}`).digest("base64url");

function connect(role, access = ticket(role), identity = auth) {
  return new Promise((resolve, reject) => {
    const socket = new WebSocket(endpoint, { headers: { "x-tethium-ticket": access, "x-tethium-role": role, "x-tethium-auth": identity } });
    socket.once("open", () => resolve(socket));
    socket.once("error", reject);
    socket.once("unexpected-response", (_request, response) => { response.destroy(); socket.terminate(); reject(new Error(`HTTP ${response.statusCode}`)); });
  });
}
function receive(socket) { return new Promise((resolve, reject) => { socket.once("message", resolve); socket.once("error", reject); }); }
function seal(plain) { const nonce=randomBytes(12), cipher=createCipheriv("aes-256-gcm",key,nonce);cipher.setAAD(Buffer.from([1]));const body=Buffer.concat([cipher.update(plain),cipher.final()]);return Buffer.concat([Buffer.from([1]),nonce,body,cipher.getAuthTag()]); }
function open(envelope) { if(envelope[0]!==1)throw new Error("version");const nonce=envelope.subarray(1,13),tag=envelope.subarray(-16),body=envelope.subarray(13,-16),cipher=createDecipheriv("aes-256-gcm",key,nonce);cipher.setAAD(Buffer.from([1]));cipher.setAuthTag(tag);return Buffer.concat([cipher.update(body),cipher.final()]); }

const host = await connect("host");
const phone = await connect("phone");
const probe = receive(host);host.send("@tethium-host-probe");if((await probe).toString()!=="@tethium-host-ok")throw new Error("host probe failed");
const one = receive(phone);host.send(seal(Buffer.from("host-to-phone")));if(open(await one).toString()!=="host-to-phone")throw new Error("host direction failed");
const two = receive(host);phone.send(seal(Buffer.from("phone-to-host")));if(open(await two).toString()!=="phone-to-host")throw new Error("phone direction failed");
try { await connect("phone", "wrong"); throw new Error("wrong ticket accepted"); } catch (error) { if(!String(error).includes("401"))throw error; }
host.terminate();phone.terminate();
console.log(JSON.stringify({ ok:true, room, encrypted:true, directions:2 }));
