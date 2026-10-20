# DeepSeek tool calling prototype (IRON-13)

Checks, before building the real narrator, that DeepSeek:

- picks the right action from a self-describing catalog (RF-API-02),
- respects the action parameters (JSON Schema, allowed stats per move),
- narrates the engine's result without inventing rolls, in neutral Latin American Spanish,
  2 to 4 sentences, without numbers and without voicing characters (RF-ENV-02, RF-ENV-12, RF-TTS-05, RF-TTS-07).

Files:

- `catalog.mjs`: test catalog (a subset of section 3.14 of the SRD) and a fake engine that validates calls and rolls the dice.
- `scenarios.mjs`: player inputs in Spanish with the expected action.
- `narrator-prompt.md`: system prompt.
- `run.mjs`: runs every scenario against the API, checks the turns and writes a report to `results/` (not versioned).

Run (PowerShell, from this folder; Node 18 or newer, no dependencies):

```powershell
$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
node run.mjs --runs 3                 # all scenarios, 3 times each, deepseek-flash
node run.mjs --model deepseek-v4-pro --effort low --only oracle,hide
node run.mjs --list-models
```

The API key lives only in the user's environment variable `DEEPSEEK_API_KEY`, never in the repository.
