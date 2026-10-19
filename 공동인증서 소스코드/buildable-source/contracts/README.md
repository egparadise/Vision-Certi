# API contracts

`openapi.dev.json` describes the initial **development-only** backend, not the planned production API. Synthetic identity headers and in-memory storage are deliberate limitations. Never use these security schemes as production authentication.

Regenerate with `python contracts/build_dev_openapi.py`. Compare the output to `apps/backend/docs/API.md` and actual controller DTOs. The generator is explicit so contract changes can be reviewed rather than inferred from prose.

Production goals (OIDC/BFF, independent approval, HSM, TSA, CA, evidence export, real delivery) remain in `docs/obsidian`. Unimplemented routes are not advertised as functional in this development contract.
