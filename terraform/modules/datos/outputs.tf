output "tabla_perfiles" {
  value = aws_dynamodb_table.perfiles.name
}

output "tabla_auditoria" {
  value = aws_dynamodb_table.auditoria.name
}

output "tabla_bandeja" {
  value = aws_dynamodb_table.bandeja_salida.name
}

output "arns_tablas" {
  description = "Para la política del rol de la tarea, incluidos los índices."
  value = [
    aws_dynamodb_table.perfiles.arn,
    aws_dynamodb_table.auditoria.arn,
    aws_dynamodb_table.bandeja_salida.arn,
    "${aws_dynamodb_table.bandeja_salida.arn}/index/*",
  ]
}

output "endpoint_cache" {
  value = aws_elasticache_serverless_cache.contadores.endpoint[0].address
}

output "puerto_cache" {
  value = aws_elasticache_serverless_cache.contadores.endpoint[0].port
}
