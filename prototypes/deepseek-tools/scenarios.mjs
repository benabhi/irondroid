// Player inputs (in Spanish, as the player would speak) and what a good narrator should do.
// expect.tools: acceptable first game action; each entry may pin move and/or stat.
// expect.none: the turn is pure fiction or a question and must not roll anything.

export const SCENARIOS = [
  {
    id: "force-door",
    input: "Intento forzar la puerta de hierro con mi hacha.",
    expect: { tools: [{ name: "roll_move", move: "face_danger", stat: "iron" }, { name: "roll_move", move: "secure_an_advantage", stat: "iron" }] },
  },
  {
    id: "hide",
    input: "Unos saqueadores se acercan por el camino. Me escondo entre las rocas para que no me vean.",
    expect: { tools: [{ name: "roll_move", move: "face_danger", stat: "shadow" }] },
  },
  {
    id: "read-runes",
    input: "Examino las runas de la puerta para entender qué dicen.",
    expect: { tools: [{ name: "roll_move", move: "gather_information", stat: "wits" }] },
  },
  {
    id: "explicit-move",
    input: "Tiro Reunir información sobre las huellas del suelo.",
    expect: { tools: [{ name: "roll_move", move: "gather_information", stat: "wits" }] },
  },
  {
    id: "oracle",
    input: "¿Hay alguien más dentro del túmulo?",
    expect: { tools: [{ name: "ask_oracle" }] },
  },
  {
    id: "compel-guardian",
    input: "Voy a la choza del viejo guardián y le pido que me ayude a abrir la puerta, recordándole la deuda que tiene con mi clan.",
    expect: { tools: [{ name: "roll_move", move: "compel", stat: "heart" }] },
  },
  {
    id: "heal-self",
    input: "Me vendo yo mismo el corte del brazo.",
    expect: { tools: [{ name: "roll_move", move: "heal", stat: "wits" }] },
  },
  {
    id: "make-camp",
    input: "Ya es de noche. Armo un campamento junto al árbol para descansar.",
    expect: { tools: [{ name: "roll_move", move: "make_camp", stat: "supply" }] },
  },
  {
    id: "pure-fiction",
    input: "Acaricio a mi caballo y le digo en voz baja que me espere aquí.",
    expect: { none: true },
  },
  {
    id: "ask-health",
    input: "¿Cuánta salud me queda?",
    expect: { none: true, allowRead: true },
  },
  {
    id: "milestone",
    input: "Entre las piedras junto a la puerta encuentro el broche de mi hermana. Es la primera prueba real de que mi familia pasó por aquí.",
    expect: { tools: [{ name: "mark_progress" }] },
  },
  {
    id: "insist-roll",
    input: "Salto el arroyo crecido sin tirar dados, seguro que lo logro.",
    expect: { tools: [{ name: "roll_move", move: "face_danger", stat: "edge" }] },
  },
];
