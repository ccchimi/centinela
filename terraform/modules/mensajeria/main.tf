# =================================================================================================
# Kafka: MSK Serverless.
#
# POR QUÉ SERVERLESS Y NO UN CLÚSTER APROVISIONADO
#
#  1. MSK Serverless SÓLO admite autenticación IAM, que es exactamente lo que ya configura
#     application-prod.yml. El mismo rol de la tarea que da acceso a DynamoDB da acceso a Kafka:
#     ni un secreto que rotar, ni un JAAS con contraseña, ni un almacén de certificados en la
#     imagen. Con un clúster aprovisionado y SASL/SCRAM habría una contraseña más que custodiar.
#
#  2. El tráfico de autorizaciones es irregular por hora del día. Un clúster aprovisionado se
#     dimensiona para el pico y se paga plano; aquí se paga por partición-hora y por GB.
#
#  3. Cero operación: sin decidir tipo de broker, sin ampliar volúmenes, sin reequilibrar
#     particiones a mano.
#
# EL COSTE DE ESTA DECISIÓN, para que quede escrito: por encima de un caudal sostenido y
# predecible (aproximadamente medio millón de mensajes por minuto de forma constante) un
# clúster de tres brokers m7g sale más barato. El día que el tráfico deje de tener valles, hay
# que rehacer este número. Serverless tampoco admite conectar Kafka Connect ni replicación
# entre regiones.
# =================================================================================================

locals {
  prefijo = "${var.nombre}-${var.entorno}"
}

resource "aws_msk_serverless_cluster" "este" {
  cluster_name = local.prefijo

  vpc_config {
    subnet_ids         = var.subredes_privadas
    security_group_ids = [var.sg_datos]
  }

  client_authentication {
    sasl {
      iam {
        enabled = true
      }
    }
  }

  tags = { Name = local.prefijo }
}

# -------------------------------------------------------------------------------------------------
# Los topics NO se crean aquí.
#
# Terraform no puede crear topics de Kafka sin un proveedor que hable el protocolo desde dentro
# de la VPC, y meter eso en el pipeline de infraestructura añade una dependencia de red frágil.
#
# Los crea la propia aplicación: ConfiguracionDeKafka declara tres beans NewTopic y KafkaAdmin
# los materializa al arrancar, con las particiones y el factor de replicación que dicen las
# propiedades. El rol de la tarea tiene permiso de CreateTopic para eso.
#
# Consecuencia a tener presente: el número de particiones lo fija la primera tarea que arranca.
# Ampliarlo después es una operación de Kafka, no un `terraform apply`.
# -------------------------------------------------------------------------------------------------
