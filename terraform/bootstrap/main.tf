# =================================================================================================
# Centinela - stack de bootstrap.
#
# Lo que tiene que existir ANTES de que el stack principal pueda aplicarse o el pipeline pueda
# desplegar. Vive aparte a propósito, por dos motivos:
#
#   1. El bucket del estado no puede estar en el stack cuyo estado guarda.
#   2. Todo lo de aquí es GRATIS o cuesta céntimos, y se aplica una sola vez. Mezclarlo con los
#      61 recursos del stack principal obligaría a aprobar el gasto entero para crear un rol.
#
#   cd terraform/bootstrap && terraform init && terraform apply
#
# =================================================================================================

data "aws_caller_identity" "actual" {}

locals {
  prefijo = "${var.nombre}-${var.entorno}"
  cuenta  = data.aws_caller_identity.actual.account_id
  sufijo  = var.sufijo_bucket_estado != "" ? var.sufijo_bucket_estado : local.cuenta

  # El `sub` que GitHub pone en el token. Tiene que coincidir EXACTAMENTE, carácter a carácter.
  sujeto_github = "repo:${var.propietario_github}/${var.repositorio_github}:ref:refs/heads/${var.rama_desplegable}"
}

# -------------------------------------------------------------------------------------------------
# Proveedor OIDC de GitHub Actions.
#
# Esto es lo que permite que el pipeline se autentique SIN una clave de acceso guardada en los
# secretos del repositorio. GitHub firma un token de identidad por ejecución, AWS lo valida contra
# este proveedor y devuelve credenciales temporales que caducan solas.
#
# No se declara `thumbprint_list`: desde 2023 AWS valida los certificados de
# token.actions.githubusercontent.com contra su propia cadena de confianza, y el provider lo
# resuelve solo. Fijar una huella a mano aquí sería volver a la época en que el pipeline se rompía
# el día que GitHub rotaba su certificado.
# -------------------------------------------------------------------------------------------------

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = []

  tags = { Name = "github-actions" }
}

# -------------------------------------------------------------------------------------------------
# Política de confianza.
#
# Las dos condiciones no son opcionales:
#
#   - `aud` = sts.amazonaws.com  descarta tokens emitidos para otro público.
#   - `sub` = este repositorio Y esta rama. SIN ESTA CONDICIÓN, CUALQUIER REPOSITORIO DE GITHUB,
#     de cualquier persona del mundo, podría asumir este rol. Es el error clásico de las guías
#     de OIDC: se copia el bloque, se deja `repo:*` y se publica una puerta abierta.
#
# StringEquals y no StringLike: un comodín en el `sub` es exactamente lo que hay que evitar. Si
# más adelante hiciera falta desplegar desde entornos o etiquetas, se añade otro valor a la lista,
# no un asterisco.
# -------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "confianza_github" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [local.sujeto_github]
    }
  }
}

resource "aws_iam_role" "despliegue" {
  name               = "${local.prefijo}-despliegue-github"
  description        = "Asumido por GitHub Actions vía OIDC para construir y desplegar Centinela"
  assume_role_policy = data.aws_iam_policy_document.confianza_github.json

  # Una hora es de sobra: el job más largo tiene un timeout de 45 minutos y las credenciales se
  # renuevan por ejecución, no se reutilizan.
  max_session_duration = 3600

  tags = { Name = "${local.prefijo}-despliegue-github" }
}

# -------------------------------------------------------------------------------------------------
# Permisos del rol: lo que el pipeline hace, y nada más.
#
# El pipeline sube una imagen a UN repositorio de ECR y actualiza UN servicio de ECS. No crea
# infraestructura: eso es competencia de Terraform, que se ejecuta con otras credenciales. Por eso
# aquí no hay ni un `ec2:*` ni un `iam:CreateRole`.
# -------------------------------------------------------------------------------------------------

data "aws_iam_policy_document" "permisos_despliegue" {

  # GetAuthorizationToken no admite permisos a nivel de recurso: o es "*" o no se puede iniciar
  # sesión en ECR. Es una limitación de la API, no una concesión.
  statement {
    sid       = "IniciarSesionEnECR"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # Subir la imagen, y consultar el escaneo de vulnerabilidades que corta el despliegue si
  # aparece algo crítico. Acotado al repositorio de Centinela: el rol no puede tocar la imagen
  # de ningún otro proyecto de la cuenta.
  statement {
    sid    = "SubirImagenAlRepositorioDeCentinela"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
      "ecr:DescribeImageScanFindings",
    ]
    resources = ["arn:aws:ecr:${var.region}:${local.cuenta}:repository/${local.prefijo}"]
  }

  # Describir y registrar definiciones de tarea tampoco admite recurso: las definiciones se
  # versionan y el ARN de la revisión nueva no existe hasta después de crearla.
  statement {
    sid    = "RegistrarDefinicionDeTarea"
    effect = "Allow"
    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
    ]
    resources = ["*"]
  }

  # Actualizar el servicio y esperar a que quede estable. Acotado al servicio de Centinela.
  statement {
    sid    = "ActualizarElServicio"
    effect = "Allow"
    actions = [
      "ecs:UpdateService",
      "ecs:DescribeServices",
    ]
    resources = ["arn:aws:ecs:${var.region}:${local.cuenta}:service/${local.prefijo}/${local.prefijo}"]
  }

  # -----------------------------------------------------------------------------------------
  # PassRole: el permiso que hay que mirar dos veces.
  #
  # Registrar una definición de tarea significa decirle a ECS "arranca este contenedor con estos
  # roles". Sin la condición de servicio, quien tenga este permiso podría pasar CUALQUIER rol de
  # la cuenta a un contenedor que él mismo define, y con ello escalar a los permisos de ese rol.
  #
  # Se acota dos veces: sólo los dos roles de Centinela, y sólo si quien los recibe es ECS.
  # -----------------------------------------------------------------------------------------
  statement {
    sid     = "PasarSoloLosRolesDeCentinelaYSoloAEcs"
    effect  = "Allow"
    actions = ["iam:PassRole"]
    resources = [
      "arn:aws:iam::${local.cuenta}:role/${local.prefijo}-ejecucion",
      "arn:aws:iam::${local.cuenta}:role/${local.prefijo}-tarea",
    ]

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "despliegue" {
  name   = "${local.prefijo}-despliegue"
  role   = aws_iam_role.despliegue.id
  policy = data.aws_iam_policy_document.permisos_despliegue.json
}

# =================================================================================================
# Estado remoto de Terraform.
#
# Sin esto, el estado del stack principal vive en el portátil de quien aplicó por última vez. Ese
# fichero contiene identificadores, ARNs y a veces valores sensibles, y si se pierde, Terraform
# deja de saber qué existe: un `apply` posterior intentaría crear de cero 61 recursos que ya están.
# =================================================================================================

resource "aws_s3_bucket" "estado" {
  bucket = "${var.nombre}-tfstate-${local.sufijo}"

  # El estado no se borra por accidente ni con un `terraform destroy` distraído.
  lifecycle {
    prevent_destroy = true
  }

  tags = { Name = "${var.nombre}-tfstate" }
}

# Versionado: es la única forma de recuperar el estado si un `apply` a medias lo deja corrupto.
# Cuesta céntimos y es la diferencia entre un mal rato y reconstruir la infraestructura a mano.
resource "aws_s3_bucket_versioning" "estado" {
  bucket = aws_s3_bucket.estado.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "estado" {
  bucket = aws_s3_bucket.estado.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Redundante con los valores por defecto de AWS desde 2023, pero explícito: un bucket con el
# estado de Terraform accesible desde internet es una filtración de toda la topología.
resource "aws_s3_bucket_public_access_block" "estado" {
  bucket = aws_s3_bucket.estado.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Las versiones antiguas del estado no hacen falta para siempre. 90 días cubre cualquier
# recuperación realista sin acumular coste.
resource "aws_s3_bucket_lifecycle_configuration" "estado" {
  bucket = aws_s3_bucket.estado.id

  rule {
    id     = "caducar-versiones-antiguas"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}
