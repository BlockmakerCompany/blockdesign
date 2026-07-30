import { promises as fs } from "node:fs";
import path from "node:path";

const textExtensions = new Set([
  ".cljc",
  ".cljs",
  ".css",
  ".html",
  ".js",
  ".json",
  ".md",
  ".mustache",
  ".po",
  ".scss",
  ".svg",
  ".txt",
]);

const rebrandFile = async filePath => {
  if (!textExtensions.has(path.extname(filePath))) return;

  const source = await fs.readFile(filePath, "utf8");
  const branded = source.replaceAll("Penpot", "BlockDesign v2.0");
  if (branded !== source) await fs.writeFile(filePath, branded);
};

const walk = async targetPath => {
  const stat = await fs.stat(targetPath);
  if (stat.isFile()) return rebrandFile(targetPath);

  const entries = await fs.readdir(targetPath, { withFileTypes: true });
  await Promise.all(entries.map(entry => walk(path.join(targetPath, entry.name))));
};

await Promise.all(process.argv.slice(2).map(walk));
