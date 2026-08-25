variable "nombre" {
  type = string
}

variable "entorno" {
  type = string
}

variable "subredes_privadas" {
  type = list(string)
}

variable "sg_datos" {
  type = string
}
