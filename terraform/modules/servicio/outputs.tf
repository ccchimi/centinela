output "dns_balanceador" {
  description = "Nombre DNS del balanceador. Apuntar aqui el registro del dominio de la API."
  value       = aws_lb.este.dns_name
}

output "nombre_cluster" {
  value = aws_ecs_cluster.este.name
}

output "nombre_servicio" {
  value = aws_ecs_service.este.name
}

output "familia_task_definition" {
  value = aws_ecs_task_definition.esta.family
}

output "arn_rol_tarea" {
  value = aws_iam_role.tarea.arn
}

output "grupo_de_logs" {
  value = aws_cloudwatch_log_group.servicio.name
}
