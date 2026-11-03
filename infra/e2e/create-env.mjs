// Independent test-only keys. Never read or modify the working lab's .env.
import { generateKeyPairSync, randomBytes } from "node:crypto";
import { existsSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const path = fileURLToPath(new URL(".env", import.meta.url));
if (!existsSync(path)) {
  const { privateKey } = generateKeyPairSync("rsa", {
    modulusLength: 3072,
    publicKeyEncoding: { type: "spki", format: "der" },
    privateKeyEncoding: { type: "pkcs8", format: "der" },
  });
  writeFileSync(
    path,
    `E2E_MASTER_KEY=${randomBytes(32).toString("base64")}\nE2E_JWT_PRIVATE_KEY=${privateKey.toString("base64")}\n`,
    { flag: "wx", mode: 0o600 },
  );
  console.log(
    "Created independent browser-test keys in ignored infra/e2e/.env.",
  );
} else {
  console.log("Keeping existing browser-test keys.");
}
