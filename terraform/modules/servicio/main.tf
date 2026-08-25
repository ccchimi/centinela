# =================================================================================================
# ECS Fargate: clúster, definición de tarea, servicio, balanceador y autoescalado.
# =================================================================================================

resource "aws_cloudwatch_log_group" "servicio" {
  name              = "/ecs/${local.prefijo}"
  retention_in_days = var.retencion_logs_dias
}

resource "aws_ecs_cluster" "este" {
  name = local.prefijo

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_ecs_cluster_capacity_providers" "este" {
  cluster_name = aws_ecs_cluster.este.name
  # Sin FARGATE_SPOT: una tarea desalojada a mitad de una evaluación es una autorización que
  # llega tarde. El ahorro no compensa en el camino de un pago.
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

# -------------------------------------------------------------------------------------------------
# Definición de tarea.
#
# La configuración entra por variables de entorno (12-Factor) y los secretos por 'secrets', que
# ECS resuelve desde Secrets Manager en el arranque: nunca aparecen en el task definition, ni en
# la consola, ni en `aws ecs describe-task-definition`.
# -------------------------------------------------------------------------------------------------

resource "aws_ecs_task_definition" "esta" {
  family                   = local.prefijo
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu_tarea
  memory                   = var.memoria_tarea
  execution_role_arn       = aws_iam_role.ejecucion.arn
  task_role_arn            = aws_iam_role.tarea.arn

  runtime_platform {
    operating_system_family = "LINUX"
    # X86_64 porque el binario nativo se compila en una imagen GraalVM x86_64. Pasar a Graviton
    # (ARM64) abarataría en torno a un 20 %, pero exige compilar la imagen para arm64: es un
    # cambio del pipeline, no de esta línea.
    cpu_architecture = "X86_64"
  }

  container_definitions = jsonencode([
    {
      name      = "centinela"
      image     = "${var.url_repositorio}:${var.etiqueta_imagen}"
      essential = true

      portMappings = [
        {
          containerPort = 8080
          protocol      = "tcp"
        }
      ]

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
        { name = "AWS_REGION", value = data.aws_region.actual.region },

        { name = "TABLA_PERFILES", value = var.tabla_perfiles },
        { name = "TABLA_AUDITORIA", value = var.tabla_auditoria },
        { name = "TABLA_BANDEJA", value = var.tabla_bandeja },

        { name = "KAFKA_BOOTSTRAP_SERVERS", value = var.bootstrap_servers },
        { name = "KAFKA_GROUP_ID", value = "${local.prefijo}-evaluador" },
        { name = "KAFKA_PARTICIONES", value = tostring(var.particiones_por_topic) },
        # MSK Serverless replica en tres zonas por su cuenta; el factor que pide el cliente al
        # crear un topic tiene que ser 3 o la creación falla.
        { name = "KAFKA_REPLICAS", value = "3" },

        { name = "REDIS_HOST", value = var.endpoint_cache },
        { name = "REDIS_PORT", value = tostring(var.puerto_cache) },
        { name = "REDIS_SSL_ENABLED", value = "true" },

        { name = "POLITICA_VERSION", value = var.version_politica },
      ]

      secrets = [
        {
          name      = "API_KEY"
          valueFrom = var.arn_secreto_api_key
        },
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.servicio.name
          "awslogs-region"        = data.aws_region.actual.region
          "awslogs-stream-prefix" = "centinela"
        }
      }

      # Sin healthCheck de contenedor: la imagen es distroless y no tiene shell ni curl con los
      # que ejecutar el comando. La salud la comprueba el grupo de destino del balanceador por
      # HTTP, que además es la señal que de verdad importa. Meter utilidades en la imagen para
      # tener un healthcheck sería cambiar seguridad por comodidad.

      stopTimeout = 30
    }
  ])
}

# -------------------------------------------------------------------------------------------------
# Balanceador
# -------------------------------------------------------------------------------------------------

resource "aws_lb" "este" {
  name               = local.prefijo
  load_balancer_type = "application"
  internal           = false
  security_groups    = [var.sg_balanceador]
  subnets            = var.subredes_publicas

  drop_invalid_header_fields = true
  enable_deletion_protection = var.proteger_contra_borrado
}

resource "aws_lb_target_group" "este" {
  name        = local.prefijo
  port        = 8080
  protocol    = "HTTP"
  vpc_id      = var.id_vpc
  target_type = "ip"

  health_check {
    path = "/actuator/health"
    # 200 y nada más: el actuator devuelve 503 cuando una dependencia está caída, y ese es
    # justo el caso en el que la tarea debe salir de rotación.
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  # El binario arranca en 0,24 s, así que no hace falta el desregistro largo que se le da a una
  # JVM; 30 s bastan para drenar las peticiones en vuelo.
  deregistration_delay = 30
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.este.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.arn_certificado

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.este.arn
  }
}

# -------------------------------------------------------------------------------------------------
# Servicio
# -------------------------------------------------------------------------------------------------

resource "aws_ecs_service" "este" {
  name            = local.prefijo
  cluster         = aws_ecs_cluster.este.id
  task_definition = aws_ecs_task_definition.esta.arn
  desired_count   = var.tareas_minimas
  launch_type     = "FARGATE"

  network_configuration {
    subnets = var.subredes_privadas
    # Las tareas NO llevan IP pública: salen por el NAT y entran sólo por el balanceador.
    assign_public_ip = false
    security_groups  = [var.sg_servicio]
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.este.arn
    container_name   = "centinela"
    container_port   = 8080
  }

  deployment_circuit_breaker {
    enable = true
    # Si las tareas nuevas no llegan a estar sanas, ECS revierte solo a la revisión anterior en
    # lugar de dejar el servicio a medio desplegar. Es lo que convierte un despliegue malo en un
    # incidente de dos minutos en vez de una noche.
    rollback = true
  }

  deployment_controller {
    type = "ECS"
  }

  # Margen antes de la primera comprobación de salud. Generoso a propósito pese al arranque de
  # 0,24 s: lo que tarda de verdad es que el consumidor se una al grupo de Kafka.
  health_check_grace_period_seconds = 60

  # El pipeline actualiza la definición de tarea; que Terraform no pelee por revertirla.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_lb_listener.https]
}

# -------------------------------------------------------------------------------------------------
# Autoescalado por CPU.
#
# Por CPU y no por número de peticiones porque el trabajo real de este servicio entra por Kafka,
# no por HTTP: escalar por peticiones al balanceador ignoraría la mitad de la carga.
# -------------------------------------------------------------------------------------------------

resource "aws_appautoscaling_target" "este" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.este.name}/${aws_ecs_service.este.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.tareas_minimas
  max_capacity       = var.tareas_maximas
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${local.prefijo}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.este.service_namespace
  resource_id        = aws_appautoscaling_target.este.resource_id
  scalable_dimension = aws_appautoscaling_target.este.scalable_dimension

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    target_value = 60

    # Ampliar rápido y reducir despacio: quedarse corto en un pico de autorizaciones cuesta
    # mucho más que una tarea de más durante cinco minutos.
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
  }
}
