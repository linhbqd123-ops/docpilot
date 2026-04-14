// Minimal Web Crypto helpers to encrypt secrets using a PEM-encoded RSA public key
export async function importPublicKeyFromPem(pem: string): Promise<CryptoKey> {
  // strip header/footer
  const b64 = pem.replace(/-----BEGIN PUBLIC KEY-----/, "").replace(/-----END PUBLIC KEY-----/, "").replace(/\s+/g, "");
  const raw = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
  return await window.crypto.subtle.importKey(
    "spki",
    raw.buffer,
    { name: "RSA-OAEP", hash: "SHA-256" },
    false,
    ["encrypt"],
  );
}

function arrayBufferToBase64(buffer: ArrayBuffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  for (let i = 0; i < bytes.byteLength; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

export async function encryptWithPublicKey(pem: string, plaintext: string): Promise<string> {
  const key = await importPublicKeyFromPem(pem);
  const enc = new TextEncoder().encode(plaintext);
  const encrypted = await window.crypto.subtle.encrypt({ name: "RSA-OAEP" }, key, enc);
  return arrayBufferToBase64(encrypted);
}
