// Test action catalog (IRON-13) shaped like RF-API-02: every action declares a name, a natural
// language description, JSON Schema parameters, what it returns, its effects and when to use it.
// A fake engine below executes the calls: it validates them and rolls the dice itself, so the
// model never decides a result.

export const MOVES = {
  face_danger: { name: "Face Danger", stats: ["edge", "heart", "iron", "shadow", "wits"], when: "Attempting something risky or reacting to an imminent threat. Edge: speed/agility/ranged; Heart: resolve/endurance; Iron: force/strength; Shadow: deception/stealth; Wits: expertise/observation." },
  secure_an_advantage: { name: "Secure an Advantage", stats: ["edge", "heart", "iron", "shadow", "wits"], when: "Assessing a situation, making preparations or trying to gain leverage before acting." },
  gather_information: { name: "Gather Information", stats: ["wits"], when: "Searching an area, asking questions, investigating or following a track." },
  compel: { name: "Compel", stats: ["heart", "iron", "shadow"], when: "Persuading someone: heart to charm or appeal, iron to threaten, shadow to lie or swindle." },
  heal: { name: "Heal", stats: ["iron", "wits"], when: "Treating an injury. Wits when tending someone else; for your own wounds roll the lower of iron and wits." },
  resupply: { name: "Resupply", stats: ["wits"], when: "Hunting, foraging or scavenging for supplies." },
  make_camp: { name: "Make Camp", stats: ["supply"], when: "Resting and recovering in the wild. Rolls +supply." },
};

const STATS = ["edge", "heart", "iron", "shadow", "wits", "supply"];
const METERS = ["health", "spirit", "supply", "momentum"];
const LIKELIHOOD = { almost_certain: 90, likely: 75, fifty_fifty: 50, unlikely: 25, small_chance: 10 };

export const TOOLS = [
  {
    name: "get_character",
    description: "Read the character sheet: stats, meters (health, spirit, supply, momentum), vows with their progress, and the current scene. Read-only. Use it when you need a value you were not given or the player asks about their character.",
    parameters: { type: "object", properties: {}, additionalProperties: false },
    returns: "The full character sheet as JSON.",
  },
  {
    name: "roll_move",
    description: "Propose an Ironsworn move to the player and, once they confirm, have the engine roll it. ALWAYS use this when the player's action is risky or uncertain and matches a move; never narrate a roll or its outcome without it. The engine rolls the dice and applies automatic effects; you only narrate the returned outcome. Available moves and the stats each allows: "
      + Object.entries(MOVES).map(([id, m]) => `${id} (${m.name}; stats: ${m.stats.join("/")}): ${m.when}`).join(" | "),
    parameters: {
      type: "object",
      properties: {
        move: { type: "string", enum: Object.keys(MOVES), description: "Move id." },
        stat: { type: "string", enum: STATS, description: "Stat to roll; must be one the move allows." },
        intent: { type: "string", description: "One short sentence, in the narrator's language, saying what the character tries to do." },
      },
      required: ["move", "stat", "intent"],
      additionalProperties: false,
    },
    returns: "dice (action die, challenge dice), outcome (strong_hit | weak_hit | miss), match (doubles), automatic effects already applied, and options when the player must choose.",
  },
  {
    name: "ask_oracle",
    description: "Ask the yes/no oracle about the world when the answer is uncertain and not up to the player (\"Is anyone inside?\"). The engine rolls; never invent the answer. Pick the likelihood from the fiction.",
    parameters: {
      type: "object",
      properties: {
        question: { type: "string", description: "Yes/no question, in the narrator's language." },
        likelihood: { type: "string", enum: Object.keys(LIKELIHOOD) },
      },
      required: ["question", "likelihood"],
      additionalProperties: false,
    },
    returns: "answer (yes | no), extreme (true on a match, meaning an extreme yes/no or a twist).",
  },
  {
    name: "adjust_meter",
    description: "Change health, spirit, supply or momentum by a delta. Use it only for costs or gains the fiction or a chosen option establishes; automatic effects of roll_move are already applied, never apply them again.",
    parameters: {
      type: "object",
      properties: {
        meter: { type: "string", enum: METERS },
        delta: { type: "integer", minimum: -6, maximum: 6, description: "Non-zero change." },
        reason: { type: "string" },
      },
      required: ["meter", "delta", "reason"],
      additionalProperties: false,
    },
    returns: "The meter's new value.",
  },
  {
    name: "mark_progress",
    description: "Mark progress on a progress track, e.g. when the player reaches a meaningful milestone on a vow (Reach a Milestone). Use the track id from the character sheet.",
    parameters: {
      type: "object",
      properties: {
        track_id: { type: "string" },
        times: { type: "integer", minimum: 1, maximum: 4, description: "How many times to mark progress; default 1." },
      },
      required: ["track_id"],
      additionalProperties: false,
    },
    returns: "The track's new progress in ticks and boxes.",
  },
  {
    name: "suggest_option",
    description: "When a roll_move result includes options, the app shows them to the player as buttons. Call this once to mark the option you recommend for the story, with a short reason. Do not list the options in your narration and do not apply the choice.",
    parameters: {
      type: "object",
      properties: {
        choice: { type: "string", description: "Exactly one of the offered choices, or for Pay the Price a short consequence written in the narrator's language." },
        why: { type: "string", description: "One short sentence in the narrator's language." },
      },
      required: ["choice", "why"],
      additionalProperties: false,
    },
    returns: "Confirmation that the suggestion is shown to the player.",
  },
  {
    name: "record_fact",
    description: "Save an important established fact to the campaign journal (a new NPC, a place, a discovery) so it is remembered later.",
    parameters: {
      type: "object",
      properties: { text: { type: "string" } },
      required: ["text"],
      additionalProperties: false,
    },
    returns: "Confirmation.",
  },
];

export function initialState() {
  return {
    name: "Kaelen",
    stats: { edge: 2, heart: 1, iron: 3, shadow: 2, wits: 1 },
    meters: { health: 4, spirit: 5, supply: 4, momentum: 2 },
    vows: [{ id: "vow-family", text: "Encontrar a mi familia antes de la primera nevada", rank: "formidable", ticks: 4 }],
    scene: "Al anochecer, frente a un túmulo antiguo en las Tierras de Hierro. El rastro de la familia termina ante una puerta sellada con hierro viejo cubierta de runas. Un caballo espera atado a un árbol. Un viejo guardián vive en una choza cercana y le debe un favor al clan de Kaelen. Kaelen tiene un corte en el brazo izquierdo.",
    journal: [],
  };
}

/** Executes tool calls against a fake game state. `rng` returns integers in [min, max]. */
export class FakeEngine {
  constructor(rng) {
    this.rng = rng;
    this.state = initialState();
    this.calls = [];
  }

  execute(name, rawArgs) {
    let args;
    try {
      args = rawArgs ? JSON.parse(rawArgs) : {};
    } catch {
      return this.#record(name, rawArgs, { error: "Arguments are not valid JSON." });
    }
    const tool = TOOLS.find(t => t.name === name);
    if (!tool) return this.#record(name, args, { error: `Unknown action '${name}'.` });
    const problem = validate(tool.parameters, args);
    if (problem) return this.#record(name, args, { error: problem });
    return this.#record(name, args, this[name](args));
  }

  #record(name, args, result) {
    this.calls.push({ name, args, result });
    return result;
  }

  get_character() {
    return this.state;
  }

  roll_move({ move, stat }) {
    const def = MOVES[move];
    if (!def.stats.includes(stat)) {
      return { error: `${def.name} cannot be rolled with ${stat}. Allowed: ${def.stats.join(", ")}.` };
    }
    const statValue = stat === "supply" ? this.state.meters.supply : this.state.stats[stat];
    const action = this.rng(1, 6);
    const challenge = [this.rng(1, 10), this.rng(1, 10)];
    const score = Math.min(action + statValue, 10);
    const beats = challenge.filter(c => score > c).length;
    const outcome = beats === 2 ? "strong_hit" : beats === 1 ? "weak_hit" : "miss";
    const result = {
      move: def.name, stat, stat_value: statValue,
      dice: { action, challenge }, action_score: score, outcome, match: challenge[0] === challenge[1],
      automatic_effects: [], options: null,
    };
    if (outcome === "strong_hit") {
      this.state.meters.momentum = Math.min(this.state.meters.momentum + 1, 10);
      result.automatic_effects.push("momentum +1 (already applied)");
    } else if (outcome === "weak_hit") {
      result.options = {
        prompt: "You succeed, but with a cost. The player chooses one; mark your suggestion.",
        choices: ["supply -1", "health -1", "spirit -1", "momentum -1"],
      };
    } else {
      result.options = {
        prompt: "You fail or your progress is undermined: Pay the Price. Propose a consequence that fits the story; the player chooses.",
        choices: ["your suggested consequence", "roll on the Pay the Price table", "the player writes their own"],
      };
    }
    return result;
  }

  ask_oracle({ likelihood }) {
    const roll = this.rng(1, 100);
    return { answer: roll <= LIKELIHOOD[likelihood] ? "yes" : "no", extreme: roll % 11 === 0 || roll === 100 };
  }

  adjust_meter({ meter, delta }) {
    if (delta === 0) return { error: "delta cannot be 0." };
    const max = meter === "momentum" ? 10 : 5;
    const min = meter === "momentum" ? -6 : 0;
    this.state.meters[meter] = Math.max(min, Math.min(max, this.state.meters[meter] + delta));
    return { meter, value: this.state.meters[meter] };
  }

  mark_progress({ track_id, times = 1 }) {
    const vow = this.state.vows.find(v => v.id === track_id);
    if (!vow) return { error: `No track '${track_id}'. Tracks: ${this.state.vows.map(v => v.id).join(", ")}.` };
    vow.ticks = Math.min(40, vow.ticks + 2 * times); // formidable: 2 ticks per mark
    return { track_id, ticks: vow.ticks, boxes: Math.floor(vow.ticks / 4) };
  }

  suggest_option({ choice }) {
    const pending = [...this.calls].reverse().find(c => c.name === "roll_move" && c.result?.options)?.result.options;
    if (!pending) return { error: "There is no pending choice to suggest an option for." };
    const isPayThePrice = pending.choices.includes("your suggested consequence");
    if (!isPayThePrice && !pending.choices.includes(choice)) return { error: `choice must be one of: ${pending.choices.join(", ")}.` };
    return { shown: true };
  }

  record_fact({ text }) {
    this.state.journal.push(text);
    return { saved: true };
  }
}

/** Minimal JSON Schema check for the shapes used above. Returns an error message or null. */
function validate(schema, args) {
  if (typeof args !== "object" || args === null || Array.isArray(args)) return "Arguments must be an object.";
  for (const key of schema.required ?? []) if (!(key in args)) return `Missing required parameter '${key}'.`;
  for (const [key, value] of Object.entries(args)) {
    const prop = schema.properties[key];
    if (!prop) return `Unknown parameter '${key}'.`;
    if (prop.type === "string" && typeof value !== "string") return `'${key}' must be a string.`;
    if (prop.type === "integer" && !Number.isInteger(value)) return `'${key}' must be an integer.`;
    if (prop.enum && !prop.enum.includes(value)) return `'${key}' must be one of: ${prop.enum.join(", ")}.`;
    if (prop.minimum !== undefined && value < prop.minimum) return `'${key}' must be >= ${prop.minimum}.`;
    if (prop.maximum !== undefined && value > prop.maximum) return `'${key}' must be <= ${prop.maximum}.`;
  }
  return null;
}

/** Translates the catalog to the OpenAI-compatible function calling format (RF-API-12). */
export function toOpenAiTools() {
  return TOOLS.map(t => ({
    type: "function",
    function: { name: t.name, description: `${t.description} Returns: ${t.returns}`, parameters: t.parameters },
  }));
}
