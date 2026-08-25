output "id_vpc" {
  value = aws_vpc.esta.id
}

output "subredes_publicas" {
  value = aws_subnet.publica[*].id
}

output "subredes_privadas" {
  value = aws_subnet.privada[*].id
}

output "sg_balanceador" {
  value = aws_security_group.balanceador.id
}

output "sg_servicio" {
  value = aws_security_group.servicio.id
}

output "sg_datos" {
  value = aws_security_group.datos.id
}
