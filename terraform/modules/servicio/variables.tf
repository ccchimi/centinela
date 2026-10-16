variable "nombre" { type = string }
variable "entorno" { type = string }

variable "id_vpc" { type = string }
variable "subredes_publicas" { type = list(string) }
variable "subredes_privadas" { type = list(string) }
variable "sg_balanceador" { type = string }
variable "sg_servicio" { type = string }

variable "url_repositorio" { type = string }
variable "etiqueta_imagen" { type = string }

variable "tabla_perfiles" { type = string }
variable "tabla_auditoria" { type = string }
variable "tabla_bandeja" { type = string }
variable "arns_tablas" { type = list(string) }

variable "bootstrap_servers" { type = string }
variable "arn_cluster_kafka" { type = string }
variable "particiones_por_topic" { type = number }

variable "endpoint_cache" { type = string }
variable "puerto_cache" { type = number }

variable "oidc_issuer_uri" {
  description = "Emisor de los tokens que acepta la API."
  type        = string
}

variable "oidc_jwk_set_uri" {
  description = "URL de las claves públicas del emisor."
  type        = string
}

variable "oidc_audiencia" {
  description = "Audiencia que deben traer los tokens."
  type        = string
}

variable "arns_secretos" {
  description = "Secretos que el rol de ejecución puede leer. Hoy ninguno: la autenticación OIDC no necesita secretos en la app."
  type        = list(string)
}

variable "arn_certificado" {
  description = "Certificado de ACM para el listener HTTPS. Sin él no hay TLS y los tokens de acceso viajarían en claro."
  type        = string
}

variable "version_politica" {
  description = "Queda grabada en cada decisión publicada; es lo primero que se pregunta al revisar una decisión antigua."
  type        = string
  default     = "v1-inicial"
}

variable "cpu_tarea" { type = number }
variable "memoria_tarea" { type = number }
variable "tareas_minimas" { type = number }
variable "tareas_maximas" { type = number }
variable "retencion_logs_dias" { type = number }

variable "proteger_contra_borrado" {
  type    = bool
  default = true
}
