import { createHash, createCipheriv, createDecipheriv, randomBytes } from "crypto";

const IV_LENGTH = 12;
const TAG_LENGTH = 16;

function deriveKey(token: string): Buffer {
  return createHash("sha256").update(token).digest();
}

export function encryptRaw(token: string, plaintext: Buffer): Buffer {
  const key = deriveKey(token);
  const iv = randomBytes(IV_LENGTH);
  const cipher = createCipheriv("aes-256-gcm", key, iv);
  const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, encrypted, tag]);
}

export function decryptRaw(token: string, data: Buffer): Buffer {
  const key = deriveKey(token);
  const iv = data.subarray(0, IV_LENGTH);
  const tag = data.subarray(data.length - TAG_LENGTH);
  const encrypted = data.subarray(IV_LENGTH, data.length - TAG_LENGTH);
  const decipher = createDecipheriv("aes-256-gcm", key, iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(encrypted), decipher.final()]);
}
