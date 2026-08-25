# =================================================================================================
# Red: VPC con subredes públicas y privadas, NAT y grupos de seguridad.
#
# Las tareas de ECS viven en subredes PRIVADAS. Sólo el balanceador está en las públicas. Un
# motor de fraude no tiene ningún motivo para ser alcanzable desde Internet salvo por el puerto
# que atiende el balanceador.
# =================================================================================================

locals {
  prefijo = "${var.nombre}-${var.entorno}"
}

resource "aws_vpc" "esta" {
  cidr_block           = var.cidr_vpc
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.prefijo }
}

resource "aws_internet_gateway" "esta" {
  vpc_id = aws_vpc.esta.id
  tags   = { Name = local.prefijo }
}

# -------------------------------------------------------------------------------------------------
# Subredes: /20 públicas y /20 privadas, una por zona.
# -------------------------------------------------------------------------------------------------

resource "aws_subnet" "publica" {
  count = length(var.zonas)

  vpc_id            = aws_vpc.esta.id
  availability_zone = var.zonas[count.index]
  cidr_block        = cidrsubnet(var.cidr_vpc, 4, count.index)

  # Sin IP pública automática: el balanceador la recibe por su propia configuración y nada más
  # debería aterrizar aquí.
  map_public_ip_on_launch = false

  tags = { Name = "${local.prefijo}-publica-${var.zonas[count.index]}" }
}

resource "aws_subnet" "privada" {
  count = length(var.zonas)

  vpc_id            = aws_vpc.esta.id
  availability_zone = var.zonas[count.index]
  cidr_block        = cidrsubnet(var.cidr_vpc, 4, count.index + length(var.zonas))

  tags = { Name = "${local.prefijo}-privada-${var.zonas[count.index]}" }
}

# -------------------------------------------------------------------------------------------------
# Salida a Internet desde las privadas.
#
# Un NAT por zona y no uno compartido: con uno solo, la caída de su zona deja sin salida a las
# tareas de las otras dos, que es justo el fallo correlacionado que las tres zonas existen para
# evitar. Cuesta más; es el precio de que la redundancia sea real.
# -------------------------------------------------------------------------------------------------

resource "aws_eip" "nat" {
  count  = length(var.zonas)
  domain = "vpc"
  tags   = { Name = "${local.prefijo}-nat-${var.zonas[count.index]}" }
}

resource "aws_nat_gateway" "este" {
  count = length(var.zonas)

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.publica[count.index].id

  depends_on = [aws_internet_gateway.esta]
  tags       = { Name = "${local.prefijo}-${var.zonas[count.index]}" }
}

resource "aws_route_table" "publica" {
  vpc_id = aws_vpc.esta.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.esta.id
  }

  tags = { Name = "${local.prefijo}-publica" }
}

resource "aws_route_table_association" "publica" {
  count          = length(var.zonas)
  subnet_id      = aws_subnet.publica[count.index].id
  route_table_id = aws_route_table.publica.id
}

resource "aws_route_table" "privada" {
  count  = length(var.zonas)
  vpc_id = aws_vpc.esta.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.este[count.index].id
  }

  tags = { Name = "${local.prefijo}-privada-${var.zonas[count.index]}" }
}

resource "aws_route_table_association" "privada" {
  count          = length(var.zonas)
  subnet_id      = aws_subnet.privada[count.index].id
  route_table_id = aws_route_table.privada[count.index].id
}

# -------------------------------------------------------------------------------------------------
# Endpoints de VPC.
#
# El tráfico a DynamoDB es el más caliente del sistema: una lectura de perfil y una escritura
# transaccional por evaluación. Con el endpoint de tipo Gateway ese tráfico no sale por el NAT,
# lo que ahorra el coste por GB procesado y quita un salto de latencia del camino crítico.
#
# ECR y CloudWatch usan endpoints de interfaz para que la extracción de imagen y los logs
# tampoco dependan del NAT.
# -------------------------------------------------------------------------------------------------

resource "aws_vpc_endpoint" "dynamodb" {
  vpc_id            = aws_vpc.esta.id
  service_name      = "com.amazonaws.${var.region}.dynamodb"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.privada[*].id

  tags = { Name = "${local.prefijo}-dynamodb" }
}

resource "aws_security_group" "endpoints" {
  name        = "${local.prefijo}-endpoints"
  description = "Endpoints de interfaz de la VPC"
  vpc_id      = aws_vpc.esta.id

  ingress {
    description = "HTTPS desde las tareas"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.cidr_vpc]
  }

  tags = { Name = "${local.prefijo}-endpoints" }
}

resource "aws_vpc_endpoint" "interfaz" {
  for_each = toset([
    "ecr.api",
    "ecr.dkr",
    "logs",
    "secretsmanager",
    "kafka",
  ])

  vpc_id              = aws_vpc.esta.id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = aws_subnet.privada[*].id
  security_group_ids  = [aws_security_group.endpoints.id]
  private_dns_enabled = true

  tags = { Name = "${local.prefijo}-${each.value}" }
}

# -------------------------------------------------------------------------------------------------
# Grupos de seguridad.
#
# Encadenados por referencia y no por CIDR: "el servicio acepta del balanceador" en vez de "el
# servicio acepta de 10.40.0.0/16". La regla dice lo que quiere decir y no se queda obsoleta si
# cambia el direccionamiento.
# -------------------------------------------------------------------------------------------------

resource "aws_security_group" "balanceador" {
  name        = "${local.prefijo}-alb"
  description = "Balanceador de la API"
  vpc_id      = aws_vpc.esta.id

  ingress {
    description = "HTTPS desde Internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = var.cidrs_permitidos
  }

  egress {
    description = "Hacia las tareas"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.cidr_vpc]
  }

  tags = { Name = "${local.prefijo}-alb" }
}

resource "aws_security_group" "servicio" {
  name        = "${local.prefijo}-servicio"
  description = "Tareas de ECS"
  vpc_id      = aws_vpc.esta.id

  egress {
    description = "Salida a AWS y al exterior via NAT"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${local.prefijo}-servicio" }
}

resource "aws_vpc_security_group_ingress_rule" "servicio_desde_balanceador" {
  security_group_id            = aws_security_group.servicio.id
  referenced_security_group_id = aws_security_group.balanceador.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
  description                  = "Solo el balanceador llega al puerto de la aplicacion"
}

resource "aws_security_group" "datos" {
  name        = "${local.prefijo}-datos"
  description = "MSK y ElastiCache"
  vpc_id      = aws_vpc.esta.id

  tags = { Name = "${local.prefijo}-datos" }
}

resource "aws_vpc_security_group_ingress_rule" "kafka_desde_servicio" {
  security_group_id            = aws_security_group.datos.id
  referenced_security_group_id = aws_security_group.servicio.id
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  description                  = "MSK con autenticacion IAM (TLS)"
}

resource "aws_vpc_security_group_ingress_rule" "redis_desde_servicio" {
  security_group_id            = aws_security_group.datos.id
  referenced_security_group_id = aws_security_group.servicio.id
  from_port                    = 6379
  to_port                      = 6380
  ip_protocol                  = "tcp"
  description                  = "ElastiCache Serverless (6379 lectura/escritura, 6380 lectura)"
}

resource "aws_vpc_security_group_egress_rule" "datos_salida" {
  security_group_id = aws_security_group.datos.id
  cidr_ipv4         = var.cidr_vpc
  ip_protocol       = "-1"
  description       = "Respuestas dentro de la VPC"
}
