output "rol_despliegue_arn" {
  description = <<-EOT
    ARN del rol que asume GitHub Actions. Este es el valor del secreto ROL_DESPLIEGUE_AWS
    (Settings > Secrets and variables > Actions > Secrets).

    Es un ARN, no una credencial: no sirve de nada sin un token OIDC firmado por GitHub que
    además venga del repositorio y la rama declarados en la política de confianza.
  EOT
  value       = aws_iam_role.despliegue.arn
}

output "sujeto_confiado" {
  description = "El `sub` exacto que se acepta. Si el workflow falla con AccessDenied, comparar con el que aparece en el error."
  value       = local.sujeto_github
}

output "bucket_estado" {
  description = "Nombre del bucket del estado remoto. Va en el bloque backend \"s3\" de terraform/versions.tf."
  value       = aws_s3_bucket.estado.id
}

output "bloque_backend" {
  description = "Bloque listo para pegar en terraform/versions.tf, sustituyendo al que está comentado."
  value       = <<-EOT
    backend "s3" {
      bucket       = "${aws_s3_bucket.estado.id}"
      key          = "${var.entorno}/terraform.tfstate"
      region       = "${var.region}"
      encrypt      = true
      use_lockfile = true
    }
  EOT
}
