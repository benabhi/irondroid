// IRON-13 prototype: does DeepSeek pick the right action from a self-describing catalog, respect
// its parameters and narrate the engine's result without inventing rolls?
//
// Usage: node run.mjs [--model deepseek-flash] [--effort low|high|max] [--runs 3] [--only id,id] [--seed 1] [--list-models]
// Needs DEEPSEEK_API_KEY in the environment. Writes results/<timestamp>-<model>.{json,md}.

import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { FakeEngine, toOpenAiTools } from "./catalog.mjs";
import { SCENARIOS } from "./scenarios.mjs";

const HERE = dirname(fileURLToPath(import.meta.url));
const BASE_URL = "https://api.deepseek.com";
const MAX_STEPS = 6;
// Voseo differs from tú by its stress (tenés, podés), so only common forms are listed: tú
// futures and "estás" also end in -ás and are correct.
const VOSEO_WORDS = /(?<!\p{L})(vos|tenés|podés|sos|querés|hacés|sabés|decís|venís|sentís|mirás|buscás|necesitás|mirá|decí|andá|vení|tomá|fijate|escuchá|esperá)(?!\p{L})/giu;

const args = parseArgs(process.argv.slice(2));
const apiKey = process.env.DEEPSEEK_API_KEY;
if (!apiKey) {
  console.error("DEEPSEEK_API_KEY is not set.");
  process.exit(2);
}

if (args["list-models"]) {
  const res = await fetch(`${BASE_URL}/models`, { headers: { Authorization: `Bearer ${apiKey}` } });
  console.log(res.status, await res.text());
  process.exit(0);
}

const model = args.model ?? "deepseek-flash";
const effort = args.effort ?? null;
const runs = Number(args.runs ?? 3);
const only = args.only ? args.only.split(",") : null;
const systemPrompt = readFileSync(join(HERE, args.prompt ?? "narrator-prompt.md"), "utf8");
const tools = toOpenAiTools();
const rng = seededRandom(Number(args.seed ?? 1));

const results = [];
for (const scenario of SCENARIOS.filter(s => !only || only.includes(s.id))) {
  for (let run = 1; run <= runs; run++) {
    const result = await playTurn(scenario);
    result.run = run;
    results.push(result);
    console.log(`${scenario.id} #${run}: ${result.verdict.pass ? "PASS" : "FAIL"} ${result.verdict.problems.join("; ")}`);
  }
}

const stamp = new Date().toISOString().replace(/[:.]/g, "-").slice(0, 19);
mkdirSync(join(HERE, "results"), { recursive: true });
const tag = `${model}${effort ? `-${effort}` : ""}`;
const base = join(HERE, "results", `${stamp}-${tag}`);
writeFileSync(`${base}.json`, JSON.stringify({ model, runs, results }, null, 2));
writeFileSync(`${base}.md`, report(tag, results));
console.log(`\nReport: ${base}.md`);

async function playTurn(scenario) {
  const engine = new FakeEngine(rng);
  const messages = [
    { role: "system", content: systemPrompt },
    { role: "user", content: `Character sheet and scene (JSON):\n${JSON.stringify(engine.state)}\n\nPlayer: ${scenario.input}` },
  ];
  const usage = { prompt_tokens: 0, completion_tokens: 0, cache_hit_tokens: 0 };
  const started = Date.now();
  let narration = "";
  let error = null;
  let reasoningChars = 0;

  for (let step = 0; step < MAX_STEPS; step++) {
    let reply;
    try {
      reply = await chat(messages);
    } catch (e) {
      error = String(e);
      break;
    }
    usage.prompt_tokens += reply.usage?.prompt_tokens ?? 0;
    usage.completion_tokens += reply.usage?.completion_tokens ?? 0;
    usage.cache_hit_tokens += reply.usage?.prompt_cache_hit_tokens ?? 0;
    const message = reply.choices[0].message;
    // Thinking models need their reasoning sent back while a tool loop is in progress.
    messages.push({ role: "assistant", content: message.content ?? "", tool_calls: message.tool_calls, reasoning_content: message.reasoning_content });
    if (message.reasoning_content) reasoningChars += message.reasoning_content.length;
    if (!message.tool_calls?.length) {
      narration = message.content ?? "";
      break;
    }
    for (const call of message.tool_calls) {
      const output = engine.execute(call.function.name, call.function.arguments);
      messages.push({ role: "tool", tool_call_id: call.id, content: JSON.stringify(output) });
    }
  }

  const calls = engine.calls;
  return {
    id: scenario.id,
    input: scenario.input,
    calls,
    narration,
    error,
    seconds: (Date.now() - started) / 1000,
    usage,
    reasoningChars,
    verdict: judge(scenario, calls, narration, error),
  };
}

async function chat(messages) {
  const res = await fetch(`${BASE_URL}/chat/completions`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({ model, messages, tools, tool_choice: "auto", ...(effort ? { reasoning_effort: effort } : {}) }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  return res.json();
}

// Automatic checks. Story coherence with the outcome is reviewed by hand in the report.
function judge(scenario, calls, narration, error) {
  const problems = [];
  if (error) problems.push(`API error: ${error}`);
  const gameCalls = calls.filter(c => c.name !== "get_character" && c.name !== "suggest_option");
  const firstGame = gameCalls[0];
  const invalid = calls.filter(c => c.result?.error);
  if (invalid.length) problems.push(`invalid calls: ${invalid.map(c => `${c.name} (${c.result.error})`).join(", ")}`);

  if (scenario.expect.none) {
    if (gameCalls.length) problems.push(`rolled or changed state with nothing at stake: ${gameCalls.map(c => c.name).join(", ")}`);
    if (!scenario.expect.allowRead && calls.some(c => c.name === "get_character")) problems.push("unneeded read");
  } else {
    const ok = scenario.expect.tools.some(t =>
      firstGame && firstGame.name === t.name
      && (!t.move || firstGame.args.move === t.move)
      && (!t.stat || firstGame.args.stat === t.stat));
    if (!ok) {
      const got = firstGame ? `${firstGame.name}(${firstGame.args.move ?? ""} ${firstGame.args.stat ?? ""})` : "no action";
      problems.push(`expected ${scenario.expect.tools.map(t => `${t.name}(${t.move ?? ""} ${t.stat ?? ""})`).join(" or ")}, got ${got}`);
    }
  }

  const rolled = calls.some(c => (c.name === "roll_move" || c.name === "ask_oracle") && !c.result?.error);
  if (!rolled && /\b(tiras?|tirada|dados?|sacas)\b/i.test(narration)) problems.push("narrates a roll without calling the engine");
  if (/\d/.test(narration)) problems.push("narration contains numbers");
  const appliedTwice = calls.some(c => c.name === "adjust_meter" && c.args.meter === "momentum" && c.args.delta > 0)
    && calls.some(c => c.name === "roll_move" && c.result?.outcome === "strong_hit");
  if (appliedTwice) problems.push("applied momentum again after a strong hit");
  const chosenForPlayer = calls.some(c => c.name === "adjust_meter") && calls.some(c => c.result?.options);
  if (chosenForPlayer) problems.push("applied an option the player should choose");

  const sentences = narration.split(/(?<=[.!?…])\s+/).filter(s => s.trim()).length;
  if (narration && (sentences < 2 || sentences > 4)) problems.push(`${sentences} sentences`);
  if (!narration && !error) problems.push("no narration");
  if (findVoseo(narration).length) problems.push(`not neutral Spanish: ${findVoseo(narration).join(", ")}`);
  if (/[«"“]|^\s*—\s*\p{Lu}/mu.test(narration)) problems.push("voices a character (dialogue)");
  const offered = calls.some(c => c.name === "roll_move" && c.result?.options);
  if (offered && !calls.some(c => c.name === "suggest_option" && !c.result?.error)) problems.push("did not mark a suggested option");

  return { pass: problems.length === 0, problems };
}

function findVoseo(text) {
  // Vosotros forms (camináis, tenéis) are Spain-only.
  const vosotros = [...text.matchAll(/(?<!\p{L})\p{L}+(áis|éis)(?!\p{L})/giu)];
  return [...new Set([...text.matchAll(VOSEO_WORDS), ...vosotros].map(m => m[0]))];
}

function report(model, results) {
  const pass = results.filter(r => r.verdict.pass).length;
  const byScenario = {};
  for (const r of results) (byScenario[r.id] ??= []).push(r);
  const tokens = results.reduce((a, r) => ({ p: a.p + r.usage.prompt_tokens, c: a.c + r.usage.completion_tokens, h: a.h + r.usage.cache_hit_tokens }), { p: 0, c: 0, h: 0 });
  const secs = results.map(r => r.seconds).sort((a, b) => a - b);
  let md = `# DeepSeek tool calling · ${model}\n\n`;
  md += `- Turns: ${results.length}, passed automatic checks: ${pass}\n`;
  md += `- Seconds per turn: median ${secs[Math.floor(secs.length / 2)].toFixed(1)}, max ${secs.at(-1).toFixed(1)}\n`;
  md += `- Tokens: ${tokens.p} prompt (${tokens.h} cache hits), ${tokens.c} completion; per turn ${Math.round(tokens.p / results.length)} / ${Math.round(tokens.c / results.length)}\n\n`;
  md += `| Scenario | Passed | Problems |\n|---|---|---|\n`;
  for (const [id, rs] of Object.entries(byScenario)) {
    md += `| ${id} | ${rs.filter(r => r.verdict.pass).length}/${rs.length} | ${[...new Set(rs.flatMap(r => r.verdict.problems))].join("; ")} |\n`;
  }
  md += `\n## Turns\n`;
  for (const r of results) {
    md += `\n### ${r.id} #${r.run} · ${r.verdict.pass ? "PASS" : "FAIL"}\n\n**Player:** ${r.input}\n\n`;
    for (const c of r.calls) md += `- \`${c.name}(${JSON.stringify(c.args)})\` → \`${JSON.stringify(c.result).slice(0, 300)}\`\n`;
    md += `\n**Narration:** ${r.narration || "(none)"}\n`;
    if (r.verdict.problems.length) md += `\n**Problems:** ${r.verdict.problems.join("; ")}\n`;
  }
  return md;
}

function parseArgs(argv) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    if (!argv[i].startsWith("--")) continue;
    const key = argv[i].slice(2);
    out[key] = argv[i + 1] && !argv[i + 1].startsWith("--") ? argv[++i] : true;
  }
  return out;
}

/** Small deterministic generator (mulberry32) so runs can be repeated with the same dice. */
function seededRandom(seed) {
  let a = seed >>> 0;
  return (min, max) => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    const r = ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    return min + Math.floor(r * (max - min + 1));
  };
}
