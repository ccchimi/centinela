output "arn_cluster" {
  value = aws_msk_serverless_cluster.este.arn
}

output "bootstrap_servers" {
  description = "Endpoint SASL/IAM. Va a KAFKA_BOOTSTRAP_SERVERS en la definición de tarea."
  value       = aws_msk_serverless_cluster.este.bootstrap_brokers_sasl_iam
}

output "nombre_cluster" {
  value = aws_msk_serverless_cluster.este.cluster_name
}
