package com.centinela.fraude.infraestructura.config;

import com.centinela.fraude.aplicacion.servicio.RelevoDeBandejaDeSalida;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Despierta al relevo del outbox cada cierto tiempo.
 *
 * <p>Es una clase de infraestructura de cinco líneas por una razón: la anotación
 * {@code @Scheduled} es un detalle del framework, y meterla en {@link RelevoDeBandejaDeSalida}
 * habría atado la capa de aplicación a Spring justo en la clase que más interesa poder probar
 * sin contexto.
 *
 * <p>{@code fixedDelay} y no {@code fixedRate}: la espera cuenta desde que <em>termina</em> el
 * ciclo anterior. Con {@code fixedRate}, un ciclo lento acumularía ejecuciones solapadas contra
 * el mismo lote de entradas pendientes.
 */
@Component
public class PlanificadorDelRelevo {

    private final RelevoDeBandejaDeSalida relevo;

    public PlanificadorDelRelevo(RelevoDeBandejaDeSalida relevo) {
        this.relevo = relevo;
    }

    @Scheduled(
            fixedDelayString = "${centinela.motor.bandeja.intervalo-de-relevo}",
            initialDelayString = "${centinela.motor.bandeja.retraso-inicial}")
    public void publicarPendientes() {
        relevo.ejecutarCiclo();
    }
}
