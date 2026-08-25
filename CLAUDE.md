# AWS Guidance

- Prefer the AWS MCP Server for AWS interactions — it provides sandboxed
  execution, observability, and audit logging. If unavailable, use the
  AWS CLI directly.
- Before starting a task, check whether a relevant AWS skill is available.
  Load the skill with `retrieve_skill` and prefer its guidance over
  general knowledge.
- When uncertain about specific AWS details (API parameters, permissions,
  limits, error codes), verify against documentation rather than guessing.
  State uncertainty explicitly if you cannot confirm.
- When creating infrastructure, prefer infrastructure-as-code (AWS CDK or
  CloudFormation) over direct CLI commands.
- When working with infrastructure, follow AWS Well-Architected Framework
  principles.
- Do not use em dashes in AWS resource names or descriptions. Use
  hyphens instead.

## Secret Safety

- MUST load the `aws-secrets-manager` skill first for any secret,
  credential, API key, token, or password task. MUST NOT call
  `secretsmanager get-secret-value` or `batch-get-secret-value`, and MUST
  NOT hit the Secrets Manager Agent daemon directly. MUST use
  `{{resolve:secretsmanager:secret-id:SecretString:json-key}}` with
  `asm-exec` so the secret resolves at runtime without entering context.

---

## Notas de este proyecto

Instalado por el Agent Toolkit de AWS (`aws-agent-rules.md`, experiencia avanzada).

Dos matices propios de Centinela:

- **La infraestructura de este repositorio es Terraform, no CDK ni CloudFormation.** La regla de
  arriba prefiere CDK/CloudFormation; aquí se eligió Terraform por decisión explícita. Vive en
  `terraform/` y está validada con `terraform validate` y `fmt`. La intención de la regla —IaC
  antes que comandos sueltos— se cumple igual.
- **La región del proyecto es `us-east-1`** (Norte de Virginia). Se movió desde `sa-east-1` para
  aprovechar el Free Tier. Coincide con la región desde la que opera el Agent Toolkit, pero son
  cosas independientes: la del proyecto la fija `var.region` en `terraform/variables.tf`.
