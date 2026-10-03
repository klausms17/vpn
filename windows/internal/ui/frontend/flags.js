// Server names often carry a flag emoji ("🇩🇪 Германия"). Windows draws
// those as two letters, so the window shows a bundled round flag instead
// (circle-flags, in flags/), or a globe when there is none.
import { el, icon } from "./util.js";

const REGIONAL_A = 0x1f1e6;

// The country code of the first flag in name, and name without it.
export function flagOf(name) {
  const cps = Array.from(name);
  for (let i = 0; i + 1 < cps.length; i++) {
    const a = cps[i].codePointAt(0) - REGIONAL_A;
    const b = cps[i + 1].codePointAt(0) - REGIONAL_A;
    if (a >= 0 && a < 26 && b >= 0 && b < 26) {
      const rest = (cps.slice(0, i).join("") + cps.slice(i + 2).join("")).replace(/\s+/g, " ").trim();
      return { code: String.fromCharCode(97 + a, 97 + b), name: rest || name };
    }
  }
  return { code: "", name };
}

export function flagBox() {
  const box = el("span", "flag");
  box.setAttribute("aria-hidden", "true");
  setFlag(box, "");
  return box;
}

// setFlag shows code's flag in box; a country without one gets the globe.
export function setFlag(box, code) {
  if (box.dataset.code === code && box.firstChild) return;
  box.dataset.code = code;
  if (!code) {
    box.replaceChildren(icon("globe"));
    return;
  }
  const img = document.createElement("img");
  img.alt = "";
  img.decoding = "async";
  img.addEventListener("error", () => box.replaceChildren(icon("globe")), { once: true });
  img.src = `flags/${code}.svg`;
  box.replaceChildren(img);
}

const PROTOCOLS = { vless: "VLESS", vmess: "VMess", trojan: "Trojan", shadowsocks: "Shadowsocks", hysteria2: "Hysteria2", hysteria: "Hysteria2" };
const SECURITY = { reality: "REALITY", tls: "TLS" };
const NETWORKS = { raw: "TCP", tcp: "TCP", ws: "WS", grpc: "gRPC", xhttp: "XHTTP", splithttp: "XHTTP", httpupgrade: "HTTPUpgrade", kcp: "mKCP" };

// What a server is, as Happ shows it: "VLESS · REALITY · TCP".
export function kindOf(p) {
  const parts = [PROTOCOLS[p.protocol] || (p.protocol || "").toUpperCase()];
  if (p.security && p.security !== "none") parts.push(SECURITY[p.security] || p.security.toUpperCase());
  if (p.network && p.protocol !== "hysteria2" && p.protocol !== "hysteria") parts.push(NETWORKS[p.network] || p.network.toUpperCase());
  return parts.filter(Boolean).join(" · ");
}
