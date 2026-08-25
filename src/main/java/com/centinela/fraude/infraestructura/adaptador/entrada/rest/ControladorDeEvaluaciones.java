package com.centinela.fraude.infraestructura.adaptador.entrada.rest;

import com.centinela.fraude.dominio.excepcion.RecursoDeDominioNoEncontrado;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.entrada.EvaluarTransaccionUseCase;
import com.centinela.fraude.dominio.puerto.salida.ActaDeDecisiones;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TraductorDeTransacciones;
import com.centinela.fraude.infraestructura.adaptador.entrada.contrato.TransaccionEvento;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador de entrada REST: evaluación síncrona y consulta del rastro de auditoría.
 *
 * <p>Es el <em>segundo</em> adaptador que invoca el mismo puerto de entrada, y esa es toda la
 * gracia del hexágono: ni el caso de uso ni una sola regla han tenido que cambiar para que
 * ahora se pueda evaluar por HTTP además de por Kafka. Comparte con el consumidor de Kafka el
 * contrato de transacción y su traductor, que viven en el paquete {@code contrato}.
 *
 * <h2>Cuándo usar cada camino</h2>
 *
 * Kafka es el flujo normal: el autorizador publica y sigue. Este endpoint existe para dos casos
 * concretos: integraciones que necesitan la decisión en la misma llamada, y la reevaluación
 * manual desde la herramienta de analistas.
 */
@RestController
@RequestMapping("/api/v1/evaluaciones")
public class ControladorDeEvaluaciones {

    private final EvaluarTransaccionUseCase evaluador;
    private final TraductorDeTransacciones traductor;
    private final ActaDeDecisiones acta;

    public ControladorDeEvaluaciones(
            EvaluarTransaccionUseCase evaluador, TraductorDeTransacciones traductor, ActaDeDecisiones acta) {
        this.evaluador = evaluador;
        this.traductor = traductor;
        this.acta = acta;
    }

    /**
     * Evalúa una transacción y devuelve el veredicto.
     *
     * <p>Responde 201 con {@code Location} apuntando al rastro de auditoría: la evaluación crea
     * un recurso consultable, no es una consulta sin efectos. La decisión va igualmente en el
     * cuerpo para que el llamante no tenga que hacer un segundo viaje.
     */
    @PostMapping
    public ResponseEntity<DecisionRespuesta> evaluar(@Valid @RequestBody TransaccionEvento peticion) {
        Transaccion transaccion = traductor.aDominio(peticion);
        EvaluacionDeFraude evaluacion = evaluador.evaluar(transaccion);

        return ResponseEntity.created(URI.create("/api/v1/evaluaciones/" + evaluacion.transaccion().valor()))
                .body(DecisionRespuesta.desde(evaluacion));
    }

    /** Rastro de auditoría de una transacción ya evaluada. */
    @GetMapping("/{idTransaccion}")
    public DecisionRespuesta consultar(@PathVariable String idTransaccion) {
        IdTransaccion id = IdTransaccion.de(idTransaccion);
        return acta.buscarPor(id)
                .map(DecisionRespuesta::desde)
                .orElseThrow(() -> RecursoDeDominioNoEncontrado.de("evaluación", idTransaccion));
    }
}
