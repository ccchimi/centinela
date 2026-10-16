# =================================================================================================
# Roles de la tarea.
#
# Son DOS y la diferencia importa:
#
#  - Rol de EJECUCIÓN: lo usa el agente de ECS, no la aplicación. Extrae la imagen de ECR,
#    escribe en CloudWatch y resuelve los secretos del task definition. La aplicación nunca
#    asume este rol.
#  - Rol de TAREA: lo asume el proceso. Es el que ve el SDK de AWS a través de su cadena de
#    credenciales por defecto, y por eso application-prod.yml no configura ninguna credencial.
#
# Confundirlos es el error clásico que acaba con un rol único con permisos de los dos.
# =================================================================================================

locals {
  prefijo = "${var.nombre}-${var.entorno}"
}

data "aws_caller_identity" "actual" {}
data "aws_region" "actual" {}

data "aws_iam_policy_document" "asumir_por_ecs" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Acota quién puede asumir el rol al propio servicio dentro de esta cuenta: sin esto, el
    # rol es asumible por el servicio ECS de cualquier cuenta (problema del "confused deputy").
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.actual.account_id]
    }
  }
}

# -------------------------------------------------------------------------------------------------
# Rol de ejecución
# -------------------------------------------------------------------------------------------------

resource "aws_iam_role" "ejecucion" {
  name               = "${local.prefijo}-ejecucion"
  assume_role_policy = data.aws_iam_policy_document.asumir_por_ecs.json
}

resource "aws_iam_role_policy_attachment" "ejecucion_gestionada" {
  role       = aws_iam_role.ejecucion.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Sólo existe si hay secretos que leer: una política con la lista de recursos vacía es inválida.
data "aws_iam_policy_document" "leer_secretos" {
  count = length(var.arns_secretos) > 0 ? 1 : 0

  statement {
    sid       = "ResolverSecretosDelTaskDefinition"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.arns_secretos
  }
}

resource "aws_iam_role_policy" "ejecucion_secretos" {
  count = length(var.arns_secretos) > 0 ? 1 : 0

  name   = "${local.prefijo}-secretos"
  role   = aws_iam_role.ejecucion.id
  policy = data.aws_iam_policy_document.leer_secretos[0].json
}

# -------------------------------------------------------------------------------------------------
# Rol de tarea: lo que la aplicación puede hacer.
#
# Sin comodines en los recursos. Cada permiso apunta a las tablas y al clúster concretos: si
# mañana esta cuenta aloja otro sistema, el motor de fraude no puede tocarlo.
# -------------------------------------------------------------------------------------------------

resource "aws_iam_role" "tarea" {
  name               = "${local.prefijo}-tarea"
  assume_role_policy = data.aws_iam_policy_document.asumir_por_ecs.json
}

data "aws_iam_policy_document" "permisos_de_la_tarea" {

  statement {
    sid = "Dynamo"
    actions = [
      "dynamodb:GetItem",
      "dynamodb:PutItem",
      "dynamodb:UpdateItem",
      "dynamodb:Query",
      # TransactWriteItems es el asiento atómico de auditoría + bandeja. Sin este permiso el
      # outbox no funciona y las decisiones no se publican.
      "dynamodb:TransactWriteItems",
    ]
    resources = var.arns_tablas
  }

  # Nótese lo que NO está: DeleteItem ni Scan. La aplicación no borra nada —el TTL se encarga—
  # y no recorre tablas: el relevo consulta el índice disperso. Conceder Scan sobre una tabla de
  # auditoría de siete años sería regalar una vía de exfiltración masiva.

  statement {
    sid = "KafkaConexion"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
      "kafka-cluster:AlterCluster",
    ]
    resources = [var.arn_cluster_kafka]
  }

  statement {
    sid = "KafkaTopics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:ReadData",
      "kafka-cluster:WriteData",
      # La aplicación declara sus topics con NewTopic al arrancar (ver el módulo de mensajería).
      "kafka-cluster:CreateTopic",
    ]
    resources = ["${local.arn_topics}/*"]
  }

  statement {
    sid       = "KafkaGrupos"
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = ["${local.arn_grupos}/*"]
  }
}

locals {
  # Los ARN de topic y grupo se derivan del ARN del clúster sustituyendo el tipo de recurso.
  partes_cluster = split(":", var.arn_cluster_kafka)
  sufijo_cluster = element(split("/", var.arn_cluster_kafka), 1)
  base_arn       = "arn:aws:kafka:${data.aws_region.actual.region}:${data.aws_caller_identity.actual.account_id}"
  nombre_cluster = element(split("/", var.arn_cluster_kafka), 1)
  uuid_cluster   = element(split("/", var.arn_cluster_kafka), 2)
  arn_topics     = "${local.base_arn}:topic/${local.nombre_cluster}/${local.uuid_cluster}"
  arn_grupos     = "${local.base_arn}:group/${local.nombre_cluster}/${local.uuid_cluster}"
}

resource "aws_iam_role_policy" "tarea" {
  name   = "${local.prefijo}-permisos"
  role   = aws_iam_role.tarea.id
  policy = data.aws_iam_policy_document.permisos_de_la_tarea.json
}
