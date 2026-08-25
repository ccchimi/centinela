output "url_repositorio" {
  value = aws_ecr_repository.este.repository_url
}

output "arn_repositorio" {
  value = aws_ecr_repository.este.arn
}

output "nombre_repositorio" {
  value = aws_ecr_repository.este.name
}
