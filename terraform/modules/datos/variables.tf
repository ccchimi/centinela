variable "nombre" {
  type = string
}

variable "entorno" {
  type = string
}

variable "sg_datos" {
  type = string
}

variable "subredes_privadas" {
  type = list(string)
}

variable "proteger_contra_borrado" {
  description = <<-EOT
    Protección contra borrado en las tablas. Activada por defecto: un `terraform destroy` mal
    dirigido se lleva por delante el rastro de auditoría de siete años y el comportamiento
    aprendido de todos los clientes. Ponerlo a false es una decisión consciente.
  EOT
  type        = bool
  default     = true
}

variable "gb_maximos_cache" {
  description = "Techo de almacenamiento de ElastiCache. Los contadores expiran solos, así que no crece."
  type        = number
  default     = 5
}

variable "ecpu_maximos_cache" {
  description = "Techo de eCPU por segundo."
  type        = number
  default     = 5000
}
