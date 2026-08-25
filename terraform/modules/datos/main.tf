# =================================================================================================
# Datos: las tres tablas de DynamoDB y la caché de contadores.
#
# Son TRES tablas y no dos. Perfiles y bandeja de salida son las que se piden explícitamente,
# pero la auditoría es la tercera pata del asiento transaccional: ActaDeDecisiones escribe
# auditoría y bandeja en un solo TransactWriteItems, y sin la tabla de auditoría no hay outbox
# que valga.
# =================================================================================================

locals {
  prefijo = "${var.nombre}-${var.entorno}"
}

# -------------------------------------------------------------------------------------------------
# Perfiles de comportamiento.
#
# Lectura por clave en cada evaluación y escritura condicionada por versión (bloqueo optimista).
# PAY_PER_REQUEST porque el tráfico de autorizaciones tiene picos marcados por hora del día y
# aprovisionar para el pico significa pagarlo las veinticuatro horas.
# -------------------------------------------------------------------------------------------------

resource "aws_dynamodb_table" "perfiles" {
  name         = "${local.prefijo}-perfiles"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "idCliente"

  attribute {
    name = "idCliente"
    type = "S"
  }

  point_in_time_recovery {
    # El perfil es estado aprendido: si se corrompe, no se puede regenerar sin reprocesar el
    # histórico entero de transacciones.
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  deletion_protection_enabled = var.proteger_contra_borrado

  tags = { Name = "${local.prefijo}-perfiles" }
}

# -------------------------------------------------------------------------------------------------
# Rastro de auditoría, con TTL.
#
# DynamoDB borra solo los items caducados: es la única forma sensata de cumplir una retención
# regulatoria de siete años sin mantener un proceso de purga propio. El atributo lo escribe
# EvaluacionDynamo como epoch en SEGUNDOS, que es lo que espera el TTL.
# -------------------------------------------------------------------------------------------------

resource "aws_dynamodb_table" "auditoria" {
  name         = "${local.prefijo}-auditoria"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "idTransaccion"

  attribute {
    name = "idTransaccion"
    type = "S"
  }

  ttl {
    attribute_name = "expiraEn"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  deletion_protection_enabled = var.proteger_contra_borrado

  tags = { Name = "${local.prefijo}-auditoria" }
}

# -------------------------------------------------------------------------------------------------
# Bandeja de salida (outbox) con ÍNDICE DISPERSO.
#
# Esta es la pieza que no se puede equivocar. La clave de partición del índice es 'estado':
# mientras la entrada está pendiente el atributo existe y la fila aparece en el índice; al
# publicarla, el adaptador ELIMINA el atributo y la fila desaparece del índice sola.
#
# Por eso el coste de cada ciclo del relevo es proporcional a lo que queda por publicar y no al
# histórico completo de decisiones. Si alguien "arreglara" esto poniendo un booleano
# publicado=true, el índice acumularía todas las decisiones jamás tomadas.
#
# Sin este índice el relevo no encuentra nada pendiente y las decisiones no se publican NUNCA,
# sin ningún error visible.
# -------------------------------------------------------------------------------------------------

resource "aws_dynamodb_table" "bandeja_salida" {
  name         = "${local.prefijo}-bandeja-salida"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "idTransaccion"

  attribute {
    name = "idTransaccion"
    type = "S"
  }

  attribute {
    name = "estado"
    type = "S"
  }

  attribute {
    name = "creadoEn"
    type = "N"
  }

  global_secondary_index {
    name = "pendientes-index"

    # key_schema y no hash_key/range_key: dentro del indice secundario, esos argumentos estan
    # deprecados en el provider 6.x. En la tabla, en cambio, siguen siendo la forma correcta.
    key_schema {
      attribute_name = "estado"
      key_type       = "HASH"
    }

    # Ordenación por antigüedad: el relevo saca primero las más viejas y un atasco no deja
    # entradas rezagadas indefinidamente.
    key_schema {
      attribute_name = "creadoEn"
      key_type       = "RANGE"
    }

    # Proyección completa: el relevo necesita la evaluación entera para publicarla. Con
    # KEYS_ONLY haría un GetItem por cada entrada del lote.
    projection_type = "ALL"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }

  deletion_protection_enabled = var.proteger_contra_borrado

  tags = { Name = "${local.prefijo}-bandeja-salida" }
}

# -------------------------------------------------------------------------------------------------
# ElastiCache Serverless (Valkey) para los contadores de velocidad.
#
# No estaba en la lista pedida, pero sin ella el servicio no arranca: ContadorDeVelocidad es uno
# de los cuatro puertos de salida y su adaptador es Redis. Serverless y no un clúster
# aprovisionado porque el volumen de los contadores es proporcional al tráfico, que es
# irregular, y aquí no hay nada que dimensionar a mano.
#
# Cifrado en tránsito activado: los contadores revelan el patrón de gasto de cada cliente.
# -------------------------------------------------------------------------------------------------

resource "aws_elasticache_serverless_cache" "contadores" {
  engine = "valkey"
  name   = "${local.prefijo}-contadores"

  cache_usage_limits {
    data_storage {
      maximum = var.gb_maximos_cache
      unit    = "GB"
    }

    ecpu_per_second {
      maximum = var.ecpu_maximos_cache
    }
  }

  daily_snapshot_time      = "03:00"
  description              = "Contadores de velocidad de Centinela"
  major_engine_version     = "8"
  snapshot_retention_limit = 1

  security_group_ids = [var.sg_datos]
  subnet_ids         = var.subredes_privadas

  tags = { Name = "${local.prefijo}-contadores" }
}
