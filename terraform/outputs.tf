output "url_repositorio_ecr" {
  description = "Destino de `docker push` del pipeline."
  value       = module.ecr.url_repositorio
}

output "dns_balanceador" {
  description = "Apuntar aqui el registro DNS de la API."
  value       = module.servicio.dns_balanceador
}

output "cluster_ecs" {
  value = module.servicio.nombre_cluster
}

output "servicio_ecs" {
  value = module.servicio.nombre_servicio
}

output "bootstrap_servers_kafka" {
  value     = module.mensajeria.bootstrap_servers
  sensitive = true
}

output "tablas_dynamodb" {
  value = {
    perfiles  = module.datos.tabla_perfiles
    auditoria = module.datos.tabla_auditoria
    bandeja   = module.datos.tabla_bandeja
  }
}

output "secreto_api_key" {
  description = "Poner el valor con `aws secretsmanager put-secret-value`; Terraform solo crea el contenedor."
  value       = aws_secretsmanager_secret.api_key.arn
}

output "grupo_de_logs" {
  value = module.servicio.grupo_de_logs
}
