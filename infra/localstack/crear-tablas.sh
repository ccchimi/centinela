#!/bin/bash
# =================================================================================================
# Crea las tablas de DynamoDB al arrancar LocalStack.
#
# Es el equivalente local de lo que en AWS hace Terraform. Vive aquí, versionado, porque el
# indice disperso del outbox no es un detalle opcional: sin el, el relevo no encuentra nada
# pendiente y las decisiones no se publican nunca, sin ningun error visible.
#
# LocalStack lo ejecuta solo por estar montado en /etc/localstack/init/ready.d/.
# =================================================================================================
set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-eu-west-1}"

echo "[centinela] creando tablas de DynamoDB en ${REGION}"

awslocal dynamodb create-table \
  --region "${REGION}" \
  --table-name centinela-perfiles \
  --attribute-definitions AttributeName=idCliente,AttributeType=S \
  --key-schema AttributeName=idCliente,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

awslocal dynamodb create-table \
  --region "${REGION}" \
  --table-name centinela-auditoria \
  --attribute-definitions AttributeName=idTransaccion,AttributeType=S \
  --key-schema AttributeName=idTransaccion,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST >/dev/null

# TTL sobre la auditoria: DynamoDB borra solo los items caducados. Es la unica forma sensata de
# cumplir una retencion de siete anios sin mantener un proceso de purga propio.
awslocal dynamodb update-time-to-live \
  --region "${REGION}" \
  --table-name centinela-auditoria \
  --time-to-live-specification "Enabled=true,AttributeName=expiraEn" >/dev/null

# Bandeja de salida con indice secundario global DISPERSO.
#
# La clave de particion del indice es 'estado'. Mientras la entrada esta pendiente el atributo
# existe y la fila aparece en el indice; al publicarla el adaptador ELIMINA el atributo y la
# fila desaparece del indice sola. Por eso el coste de cada ciclo del relevo es proporcional a
# lo que queda por publicar y no al historico completo de decisiones.
awslocal dynamodb create-table \
  --region "${REGION}" \
  --table-name centinela-bandeja-salida \
  --attribute-definitions \
      AttributeName=idTransaccion,AttributeType=S \
      AttributeName=estado,AttributeType=S \
      AttributeName=creadoEn,AttributeType=N \
  --key-schema AttributeName=idTransaccion,KeyType=HASH \
  --global-secondary-indexes \
      '[{"IndexName":"pendientes-index",
         "KeySchema":[{"AttributeName":"estado","KeyType":"HASH"},
                      {"AttributeName":"creadoEn","KeyType":"RANGE"}],
         "Projection":{"ProjectionType":"ALL"}}]' \
  --billing-mode PAY_PER_REQUEST >/dev/null

echo "[centinela] tablas listas: perfiles, auditoria y bandeja-salida (con indice pendientes-index)"
