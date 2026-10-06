# Immich OpenAPI lock

Official sources:

- `https://raw.githubusercontent.com/immich-app/immich/v2.6.0/open-api/immich-openapi-specs.json`
- `https://raw.githubusercontent.com/immich-app/immich/v2.6.3/open-api/immich-openapi-specs.json`
- `https://raw.githubusercontent.com/immich-app/immich/v3.1.0/open-api/immich-openapi-specs.json`

SHA-256 v2.6.0: `CBA07145B978F958FD992B6A4F199B82CD0E398B653334C4A767275B9AE66F33`

SHA-256 v2.6.3: `2B5ECBC0E2127C8F6785E16E1C87698F80DB6E8BC44AFB387DEF9EFA42D429B2`

SHA-256 v3.1.0: `BC6712603E980FAEF3277D2417B123A5787ED445B077F084B3B181B098F856DA`

These files are the official specifications of the Immich project, distributed under the licence
of its repository. The app generates the subset it needs from the minimum contract, v2.6.0, and
checks the same `operationId`s against v2.6.3 and v3.1.0 on every build.
