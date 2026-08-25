variable "nombre" {
  type = string
}

variable "entorno" {
  type = string
}

variable "region" {
  type = string
}

variable "cidr_vpc" {
  type = string
}

variable "zonas" {
  type = list(string)
}

variable "cidrs_permitidos" {
  description = <<-EOT
    Quién puede llegar al balanceador. El valor por defecto es 0.0.0.0/0 sólo para que el módulo
    sea utilizable tal cual; en un despliegue real esto debe acotarse a los rangos del
    autorizador y de la red corporativa. La clave de API es la segunda barrera, no la primera.
  EOT
  type        = list(string)
  default     = ["0.0.0.0/0"]
}
