terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # ---------------------------------------------------------------------------------------------
  # SIN backend remoto, y aquí sí es lo correcto.
  #
  # Este stack CREA el bucket donde vivirá el estado del stack principal. Si su propio estado
  # viviera en ese bucket, haría falta que el bucket existiera para poder crear el bucket.
  #
  # El estado de este stack es pequeño y casi inmutable —un proveedor OIDC, un rol y un bucket,
  # que se tocan una vez al año— así que conservar el `terraform.tfstate` local y respaldarlo
  # es una respuesta razonable. Lo que NO se puede hacer es perderlo: sin él, un `apply`
  # posterior intentaría recrear el proveedor OIDC y fallaría con EntityAlreadyExists.
  # ---------------------------------------------------------------------------------------------
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Proyecto      = "centinela"
      Entorno       = "compartido"
      GestionadoPor = "terraform"
      Stack         = "bootstrap"
    }
  }
}
