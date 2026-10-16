# =================================================================================================
# Centinela - composición de la infraestructura.
#
# El orden de las dependencias lo resuelve Terraform solo a partir de las referencias; aquí los
# módulos van en el orden en que se leen mejor: red, registro, datos, mensajería y servicio.
# =================================================================================================

module "red" {
  source = "./modules/red"

  nombre   = var.nombre
  entorno  = var.entorno
  region   = var.region
  cidr_vpc = var.cidr_vpc
  zonas    = var.zonas_de_disponibilidad

  # Acotar esto a los rangos del autorizador y de la red corporativa antes de exponer nada.
  # El token de acceso es la segunda barrera, no la primera.
  cidrs_permitidos = var.cidrs_permitidos
}

module "ecr" {
  source = "./modules/ecr"

  nombre  = var.nombre
  entorno = var.entorno
}

module "datos" {
  source = "./modules/datos"

  nombre            = var.nombre
  entorno           = var.entorno
  sg_datos          = module.red.sg_datos
  subredes_privadas = module.red.subredes_privadas
}

module "mensajeria" {
  source = "./modules/mensajeria"

  nombre            = var.nombre
  entorno           = var.entorno
  subredes_privadas = module.red.subredes_privadas
  sg_datos          = module.red.sg_datos
}

# -------------------------------------------------------------------------------------------------
# Autenticación de la API: JWT de un proveedor OIDC.
#
# A diferencia de la antigua clave de API, aquí no hay ningún secreto que guardar: la app sólo
# necesita saber quién emite los tokens (issuer), de dónde bajar sus claves PÚBLICAS (JWKS) y qué
# audiencia exigir. Los secretos son de cada cliente (el autorizador, la herramienta de analistas)
# y viven en el proveedor de identidad, no en esta infraestructura.
# -------------------------------------------------------------------------------------------------

module "servicio" {
  source = "./modules/servicio"

  nombre  = var.nombre
  entorno = var.entorno

  id_vpc            = module.red.id_vpc
  subredes_publicas = module.red.subredes_publicas
  subredes_privadas = module.red.subredes_privadas
  sg_balanceador    = module.red.sg_balanceador
  sg_servicio       = module.red.sg_servicio

  url_repositorio = module.ecr.url_repositorio
  etiqueta_imagen = var.etiqueta_imagen

  tabla_perfiles  = module.datos.tabla_perfiles
  tabla_auditoria = module.datos.tabla_auditoria
  tabla_bandeja   = module.datos.tabla_bandeja
  arns_tablas     = module.datos.arns_tablas

  bootstrap_servers     = module.mensajeria.bootstrap_servers
  arn_cluster_kafka     = module.mensajeria.arn_cluster
  particiones_por_topic = var.particiones_por_topic

  endpoint_cache = module.datos.endpoint_cache
  puerto_cache   = module.datos.puerto_cache

  oidc_issuer_uri  = var.oidc_issuer_uri
  oidc_jwk_set_uri = coalesce(var.oidc_jwk_set_uri, "${var.oidc_issuer_uri}/protocol/openid-connect/certs")
  oidc_audiencia   = var.oidc_audiencia
  arns_secretos    = []
  arn_certificado  = var.arn_certificado

  cpu_tarea           = var.cpu_tarea
  memoria_tarea       = var.memoria_tarea
  tareas_minimas      = var.tareas_minimas
  tareas_maximas      = var.tareas_maximas
  retencion_logs_dias = var.retencion_logs_dias
}
