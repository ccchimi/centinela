terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # ---------------------------------------------------------------------------------------------
  # Backend remoto con bloqueo. Se deja comentado a propósito: el bucket y la tabla de bloqueo
  # tienen que existir ANTES del primer `terraform init`, así que se crean una sola vez a mano o
  # con un stack aparte. Ponerlos aquí crearia una dependencia circular.
  #
  # Sin backend remoto, el estado vive en el portátil de quien aplicó por última vez, y el
  # estado de Terraform contiene identificadores y a veces valores sensibles.
  # ---------------------------------------------------------------------------------------------
  # backend "s3" {
  #   bucket       = "centinela-tfstate-<sufijo>"
  #   key          = "prod/terraform.tfstate"
  #   region       = "sa-east-1"
  #   encrypt      = true
  #   use_lockfile = true
  # }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Proyecto      = "centinela"
      Entorno       = var.entorno
      GestionadoPor = "terraform"
    }
  }
}
