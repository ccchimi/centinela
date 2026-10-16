# Despliegue en AWS

Runbook de la rama `centinela-aws-deploy`. Deja el proyecto en el punto exacto en que un
`terraform apply` es lo único que separa el código de una infraestructura funcionando.

> **Estado: todo lo gratuito está resuelto en código; falta el gasto.**
>
> De los ocho pasos, siete no cuestan dinero y están escritos, validados y con su `plan`. El
> único que consume presupuesto es el 5, y es el que manda: sin él no hay ECR ni ECS a los que
> desplegar, así que los pasos 6, 7 y 8 dependen de él.

- [Lo que ya está resuelto](#lo-que-ya-está-resuelto)
- [Los dos stacks](#los-dos-stacks)
- [Paso a paso](#paso-a-paso)
- [El coste, con números reales](#el-coste-con-números-reales)
- [Diagnóstico](#diagnóstico)

---

## Lo que ya está resuelto

| # | Paso | Estado | Coste |
|---|---|---|---|
| 1 | Proveedor OIDC + rol de IAM | **En código.** `terraform/bootstrap/` | Gratis |
| 2 | Certificado de ACM | Pendiente: **necesita un dominio** | Gratis (el certificado) |
| 3 | `terraform.tfvars` | Plantilla completa y documentada | Gratis |
| 4 | Bucket del estado remoto | **En código.** `terraform/bootstrap/` | Céntimos |
| 5 | **`terraform apply` del stack principal** | Esperando presupuesto | **~950 USD/mes** |
| 6 | Emisor OIDC (`oidc_issuer_uri`) | Pendiente: **necesita un Keycloak u otro proveedor OIDC con HTTPS** | Depende del proveedor |
| 7 | Secreto `ROL_DESPLIEGUE_AWS` | Listo tras el 1 | Gratis |
| 8 | Variable `DESPLIEGUE_HABILITADO` | Último interruptor | Gratis |

Además, en esta rama se corrigió una carencia que sólo se ve al pensar en el arranque real: **el
gateway endpoint de S3**. ECR guarda las capas de imagen en S3 y sólo los metadatos en el
registro, así que sin ese endpoint —y con las tareas en subredes privadas— cada arranque baja
338 MB por el NAT Gateway y paga el procesamiento por GB. Los endpoints de tipo Gateway no
tienen coste horario: es ahorro puro. El stack principal pasó de 61 a 62 recursos.

## Los dos stacks

```
terraform/
├── bootstrap/     8 recursos    gratis / céntimos    se aplica UNA vez
└── (raíz)        62 recursos    ~950 USD/mes         el despliegue de verdad
```

El bootstrap está separado por dos razones, y ninguna es estética:

1. **El bucket del estado no puede vivir en el stack cuyo estado guarda.** Es el huevo y la
   gallina que el comentario de `versions.tf` ya anticipaba.
2. **Aplicarlo no debería requerir aprobar el gasto.** Crear un rol de IAM es gratis. Si
   estuviera en el stack principal, habría que aplicar los 62 recursos para tenerlo.

### Qué crea el bootstrap

```
aws_iam_openid_connect_provider.github
aws_iam_role.despliegue
aws_iam_role_policy.despliegue
aws_s3_bucket.estado
aws_s3_bucket_versioning.estado
aws_s3_bucket_server_side_encryption_configuration.estado
aws_s3_bucket_public_access_block.estado
aws_s3_bucket_lifecycle_configuration.estado
```

Dos detalles del rol que conviene entender antes de aplicarlo:

**La condición `sub` está acotada al repositorio Y a la rama.** Sin ella, cualquier repositorio de
GitHub, de cualquier persona del mundo, podría asumir este rol. Es el error clásico de las guías
de OIDC: se copia el bloque, se deja un comodín y se publica una puerta abierta. Aquí es
`StringEquals`, no `StringLike`, con el valor exacto:

```
repo:ccchimi/centinela:ref:refs/heads/main
```

**`iam:PassRole` está acotado dos veces.** Registrar una definición de tarea significa decirle a
ECS "arranca este contenedor con estos roles". Sin acotar, quien tenga ese permiso podría pasar
cualquier rol de la cuenta a un contenedor que él mismo define, y escalar a los permisos de ese
rol. La política permite pasar sólo los dos roles de Centinela, y sólo si quien los recibe es
`ecs-tasks.amazonaws.com`.

---

## Paso a paso

### 0. Credenciales

```bash
aws login --profile centinela
```

```bash
aws sts get-caller-identity --profile centinela
```

> La sesión caduca sola. Si un comando falla con `NoCredentials` o
> `The provided authorization grant is invalid, expired, revoked, or malformed`, es esto.

### 1. Aplicar el bootstrap — *gratis*

```bash
cd terraform/bootstrap && terraform init
```

```bash
terraform plan
```

Debe decir `Plan: 8 to add, 0 to change, 0 to destroy`. Comprueba en la salida que
`sujeto_confiado` es exactamente tu repositorio y tu rama: equivocarse ahí no da un error, da un
rol que otro repositorio puede asumir o uno que el tuyo no puede.

```bash
terraform apply
```

Guarda las salidas: `rol_despliegue_arn`, `bucket_estado` y `bloque_backend`.

> **El `terraform.tfstate` de este stack es local y no se puede perder.** Sin él, un `apply`
> posterior intentaría recrear el proveedor OIDC y fallaría con `EntityAlreadyExists`. Cópialo a
> algún sitio seguro.

### 2. Certificado de ACM — *gratis, pero necesita un dominio*

**Este es el paso que hoy no se puede completar**: la cuenta no tiene ningún dominio registrado ni
ninguna zona en Route53, y un certificado público de ACM se valida demostrando control sobre un
dominio.

Las opciones, por si sirve de referencia:

| Opción | Coste | Nota |
|---|---|---|
| Registrar un dominio en Route53 | ~12-15 USD/año | Cargo inmediato y no reembolsable |
| Usar un dominio que ya tengas en otro proveedor | Gratis | Hay que crear el CNAME de validación a mano en su panel |

```bash
aws acm request-certificate --domain-name api.tudominio.com --validation-method DNS --region us-east-1
```

```bash
aws acm describe-certificate --certificate-arn <arn> --region us-east-1 --query 'Certificate.DomainValidationOptions'
```

Crea el CNAME que devuelve ese comando y espera a que el estado pase a `ISSUED`. Un certificado
en `PENDING_VALIDATION` deja el listener creado pero sin servir tráfico.

### 3. Configurar el stack principal

```bash
cd terraform && cp terraform.tfvars.ejemplo terraform.tfvars
```

Edita `terraform.tfvars`. Tres valores importan de verdad:

- **`arn_certificado`** — el del paso anterior. Sin valor por defecto.
- **`oidc_issuer_uri`** — el emisor de los tokens (ver el paso 6). Sin valor por defecto y tiene
  que ser HTTPS: la validación de Terraform corta el `plan` si no lo es.
- **`cidrs_permitidos`** — hoy el default es `0.0.0.0/0`, que expone la API a todo internet con
  el token como única barrera. Acótalo a los rangos del autorizador.

`terraform.tfvars` está en `.gitignore` y no debe subirse.

### 4. Estado remoto

Sustituye el bloque `backend "s3"` comentado en [`terraform/versions.tf`](terraform/versions.tf)
por el que devolvió la salida `bloque_backend` del bootstrap, y reinicializa:

```bash
terraform init -migrate-state
```

### 5. El apply — *aquí está el gasto*

```bash
terraform plan -out=prod.tfplan
```

Revisa que diga `Plan: 62 to add, 0 to change, 0 to destroy` y **lee el plan entero** antes de
seguir. A partir del siguiente comando el reloj corre.

```bash
terraform apply prod.tfplan
```

Tarda entre 20 y 40 minutos: MSK Serverless y ElastiCache son los lentos.

### 6. El proveedor de identidad

La API valida JWT, así que necesita un emisor OIDC alcanzable por HTTPS desde las tareas (para
descargar sus claves públicas). Con Keycloak:

1. Importar [`infra/keycloak/centinela-realm.json`](infra/keycloak/centinela-realm.json) como
   punto de partida y **regenerar los secretos de los clientes**: los del fichero son de
   desarrollo y públicos.
2. Entregar a cada consumidor su `client_id` y su secreto por un canal seguro. El autorizador
   recibe el rol `evaluador`; la herramienta de analistas, `analista`.
3. Poner la URL del realm en `oidc_issuer_uri`.

La app no guarda ningún secreto: emisor, claves públicas y audiencia son datos públicos. Si falta
`OIDC_ISSUER_URI` la aplicación no arranca, en lugar de quedarse aceptando cualquier cosa.

### 7. El secreto del repositorio — *gratis*

En *Settings › Secrets and variables › Actions › Secrets*, crea:

| Nombre | Valor |
|---|---|
| `ROL_DESPLIEGUE_AWS` | La salida `rol_despliegue_arn` del bootstrap |

Es un ARN, no una credencial: no sirve de nada sin un token OIDC firmado por GitHub que además
venga del repositorio y la rama declarados en la política de confianza.

### 8. Encender el pipeline — *gratis*

En *Settings › Secrets and variables › Actions › Variables*:

| Nombre | Valor |
|---|---|
| `DESPLIEGUE_HABILITADO` | `true` |

**Éste es el último paso, no el primero.** Con ECR y ECS ya creados, el siguiente push a `main`
construye la imagen nativa, la sube etiquetada con el SHA del commit, espera al escaneo de
vulnerabilidades y actualiza el servicio.

---

## El coste, con números reales

Consultado contra la API de precios de AWS para `us-east-1`, 730 h/mes:

| Concepto | Tarifa | Mensual |
|---|---|---|
| MSK Serverless — cluster | 0,75 USD / cluster-hora | **547,50** |
| MSK Serverless — 18 particiones | 0,0015 USD / partición-hora | 19,71 |
| VPC endpoints de interfaz (5 × 3 AZ = 15 ENI) | 0,01 USD / endpoint-hora | 109,50 |
| NAT Gateway × 3 | 0,045 USD / hora | 98,55 |
| | | **775,26 verificado** |

Fargate (2 × 1 vCPU/2 GB), ALB, ElastiCache Serverless y transferencia de datos no se pudieron
confirmar contra la API con los filtros usados; **son estimaciones**, no datos. Con ellos el total
realista ronda los **950-1.000 USD/mes**.

El Free Tier cubre ALB (750 h), DynamoDB (25 GB), ECR (500 MB) y CloudWatch (5 GB). **No cubre
ninguno de los cuatro conceptos de la tabla.**

### Si el presupuesto es el bloqueante

Una variante de desarrollo baja a **~78 USD/mes** sin tocar el perfil `prod`:

| Cambio | De → A | Ahorro |
|---|---|---|
| MSK Serverless → MSK provisionado, 1 broker `kafka.t3.small`, 1 AZ | 567 → ~36 | **~530** |
| Quitar los 3 NAT (los gateway endpoints ya cubren S3 y DynamoDB) | 98,55 → 0 | ~99 |
| Endpoints de interfaz sólo en 1 AZ | 109,50 → 36,50 | ~73 |
| ElastiCache Serverless → `cache.t4g.micro` (entra en Free Tier) | ~90 → 0 | ~90 |
| Fargate: 1 tarea de 0.25 vCPU / 0.5 GB | ~70 → ~9 | ~61 |

MSK provisionado habla el mismo protocolo con la misma autenticación IAM, así que **es cero
cambios en la aplicación**.

---

## Diagnóstico

| Síntoma | Causa |
|---|---|
| El workflow falla con `Could not assume role` o `AccessDenied` | El `sub` del token no coincide con la condición del rol. Compara el `sub` del mensaje de error con la salida `sujeto_confiado` del bootstrap. Suele ser la rama |
| El job de imagen nativa falla en `configure-aws-credentials` a los pocos segundos | Falta el secreto `ROL_DESPLIEGUE_AWS`, y GitHub lo resuelve como cadena vacía |
| Los jobs de despliegue aparecen como `Skipped` | Falta la variable `DESPLIEGUE_HABILITADO = true`. Es el comportamiento por defecto y es intencionado |
| Las tareas se quedan en `PENDING` y mueren al extraer la imagen | Falta el gateway endpoint de S3 o la salida a internet. ECR guarda las capas en S3 |
| El servicio recicla tareas en bucle | Falta `OIDC_ISSUER_URI` y el contenedor no arranca, así que el health check nunca pasa |
| La API responde `401` a tokens recién emitidos | El `iss` del token no coincide con `oidc_issuer_uri` (ojo con la barra final y http/https) o falta la audiencia `centinela-api` en el cliente |
| `terraform apply` pide `arn_certificado` por consola | No existe `terraform.tfvars`, o no tiene esa variable. No tiene valor por defecto a propósito |
| `EntityAlreadyExists` al aplicar el bootstrap | Se perdió su `terraform.tfstate` local. Hay que importar los recursos con `terraform import` |
