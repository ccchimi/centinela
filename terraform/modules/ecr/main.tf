# =================================================================================================
# Registro de imágenes.
# =================================================================================================

resource "aws_ecr_repository" "este" {
  name = "${var.nombre}-${var.entorno}"

  # Etiquetas INMUTABLES. Es la diferencia entre "la versión 3a7f1c es esto" y "la versión
  # 3a7f1c es lo que hubiera cuando alguien la sobrescribió". En un sistema que decide
  # autorizaciones de pago, poder reproducir exactamente el binario que tomó una decisión de
  # hace tres meses no es opcional.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }

  tags = { Name = "${var.nombre}-${var.entorno}" }
}

# -------------------------------------------------------------------------------------------------
# Política de ciclo de vida.
#
# Las imágenes nativas pesan ~340 MB. Sin purga, un pipeline activo llena el registro de
# capas que nadie va a volver a desplegar y la factura crece en silencio.
# -------------------------------------------------------------------------------------------------

resource "aws_ecr_lifecycle_policy" "este" {
  repository = aws_ecr_repository.este.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Conservar las 20 imagenes etiquetadas mas recientes"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 20
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Borrar las no etiquetadas tras un dia"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 1
        }
        action = { type = "expire" }
      },
    ]
  })
}
