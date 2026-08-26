variable "region" {
  description = "Región de AWS. Debe coincidir con la del stack principal."
  type        = string
  default     = "us-east-1"
}

variable "nombre" {
  description = "Prefijo común de los recursos. Debe coincidir con el del stack principal."
  type        = string
  default     = "centinela"
}

variable "entorno" {
  description = "Entorno al que despliega el pipeline. Debe coincidir con el del stack principal."
  type        = string
  default     = "prod"
}

# -------------------------------------------------------------------------------------------------
# Identidad del repositorio de GitHub.
#
# Estos tres valores forman la condición `sub` del rol. Equivocarse aquí no da un error: da un rol
# que otro repositorio puede asumir, o uno que el tuyo no puede. Comprobar el ARN en la salida del
# workflow es la única forma de saber que está bien.
# -------------------------------------------------------------------------------------------------

variable "propietario_github" {
  description = "Usuario u organización dueña del repositorio."
  type        = string
  default     = "ccchimi"
}

variable "repositorio_github" {
  description = "Nombre del repositorio, sin el propietario."
  type        = string
  default     = "centinela"
}

variable "rama_desplegable" {
  description = <<-EOT
    Única rama desde la que se permite asumir el rol. Con `main` aquí, un workflow en una rama
    de trabajo NO puede desplegar aunque alguien lo escriba: es la diferencia entre "hay que
    revisar los pull requests" y "no se puede desplegar sin pasar por main".
  EOT
  type        = string
  default     = "main"
}

# -------------------------------------------------------------------------------------------------
# Estado remoto
# -------------------------------------------------------------------------------------------------

variable "sufijo_bucket_estado" {
  description = <<-EOT
    Sufijo del bucket del estado. Los nombres de bucket son ÚNICOS EN TODO AWS, no por cuenta,
    así que "centinela-tfstate" está tomado casi con seguridad. Se usa el identificador de cuenta
    por defecto, que es único y no revela nada que un ARN no revele ya.

    Dejarlo vacío usa el id de la cuenta.
  EOT
  type        = string
  default     = ""
}
