variable "region" {
  description = "Región de AWS. us-east-1 (Norte de Virginia) tiene DynamoDB, ElastiCache, ECR, ECS, MSK, Secrets Manager y ELB."
  type        = string
  default     = "us-east-1"
}

variable "entorno" {
  description = "Nombre del entorno; entra en el nombre de todos los recursos."
  type        = string
  default     = "prod"
}

variable "nombre" {
  description = "Prefijo común de los recursos."
  type        = string
  default     = "centinela"
}

# -------------------------------------------------------------------------------------------------
# Red
# -------------------------------------------------------------------------------------------------

variable "cidr_vpc" {
  description = "Rango de la VPC."
  type        = string
  default     = "10.40.0.0/16"
}

variable "zonas_de_disponibilidad" {
  description = <<-EOT
    Zonas a usar. Tres y no dos: MSK Serverless y una tabla de decisiones de pago no deberían
    quedarse sin quórum porque una zona tenga un mal día.
  EOT
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

# -------------------------------------------------------------------------------------------------
# Servicio
# -------------------------------------------------------------------------------------------------

variable "etiqueta_imagen" {
  description = "Etiqueta de la imagen en ECR. El pipeline la fija al SHA del commit, nunca a 'latest'."
  type        = string
  default     = "latest"
}

variable "cpu_tarea" {
  description = <<-EOT
    Unidades de CPU por tarea (1024 = 1 vCPU). El binario nativo arranca en 0,24 s y consume
    ~100 MiB en reposo, así que la tarea se dimensiona por el trabajo de evaluación, no por el
    calentamiento de una JVM.
  EOT
  type        = number
  default     = 1024
}

variable "memoria_tarea" {
  description = "MiB por tarea. 2 GB da holgura sobre los ~100 MiB de reposo para picos de tráfico."
  type        = number
  default     = 2048
}

variable "tareas_minimas" {
  description = "Mínimo de tareas. Dos, no una: una sola tarea significa una ventana sin servicio en cada despliegue."
  type        = number
  default     = 2
}

variable "tareas_maximas" {
  description = "Techo del autoescalado."
  type        = number
  default     = 10
}

# -------------------------------------------------------------------------------------------------
# Kafka
# -------------------------------------------------------------------------------------------------

variable "particiones_por_topic" {
  description = <<-EOT
    Debe ser >= a spring.kafka.listener.concurrency por tarea x número de tareas, o habrá
    consumidores ociosos. Con 3 de concurrencia y 2 tareas, 6 es el mínimo útil.
  EOT
  type        = number
  default     = 6
}

# -------------------------------------------------------------------------------------------------
# Retención
# -------------------------------------------------------------------------------------------------

variable "retencion_logs_dias" {
  description = "Retención de CloudWatch Logs. 30 días cubre una investigación normal sin acumular coste."
  type        = number
  default     = 30
}

variable "cidrs_permitidos" {
  description = "Rangos que pueden llegar al balanceador. Acotar antes de exponer la API."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "arn_certificado" {
  description = <<-EOT
    Certificado de ACM para el listener HTTPS. Sin TLS, la clave de la cabecera X-API-KEY
    viajaria en claro y cualquiera en el camino podria reutilizarla. No tiene valor por defecto
    a proposito: obliga a resolverlo antes del primer apply.
  EOT
  type        = string
}
