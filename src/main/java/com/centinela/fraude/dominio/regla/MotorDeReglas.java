package com.centinela.fraude.dominio.regla;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Ejecuta el catálogo de reglas sobre un contexto y agrega los resultados.
 *
 * <p>Es una clase del dominio: sin anotaciones, sin inyección, sin log. Se construye pasándole
 * la lista de reglas, y por eso se puede instanciar en un test con dos reglas de mentira y
 * comprobar el agregado en microsegundos.
 *
 * <h2>Aislamiento de fallos</h2>
 *
 * Una regla que lanza no puede hundir la autorización. El motor la captura, la convierte en
 * {@link ResultadoDeRegla.Fallida} y sigue con las demás. Es la diferencia entre "una regla
 * nueva tiene un bug" y "el motor deja de responder": lo primero degrada la cobertura, lo
 * segundo tumba el sistema de pagos.
 *
 * <h2>Determinismo</h2>
 *
 * Las reglas se ejecutan en el orden en que se declararon y ese orden se conserva en las
 * señales. No se paralelizan: son funciones puras de coste microscópico, y repartirlas en
 * hilos costaría más en coordinación de lo que ahorraría. La concurrencia de este sistema está
 * donde está la E/S, en el caso de uso, no aquí.
 */
public final class MotorDeReglas {

    private final List<ReglaDeFraude> reglas;

    public MotorDeReglas(List<ReglaDeFraude> reglas) {
        Invariantes.noNulo(reglas, "reglas");
        Invariantes.exigir(!reglas.isEmpty(), "El motor necesita al menos una regla");

        Set<String> codigos = new HashSet<>();
        for (ReglaDeFraude regla : reglas) {
            Invariantes.noNulo(regla, "regla");
            String codigo = Invariantes.textoConContenido(regla.codigo(), "regla.codigo");
            Invariantes.exigir(
                    codigos.add(codigo),
                    "Código de regla duplicado: '%s'. Los códigos identifican pesos, métricas y "
                            + "documentación de cumplimiento, y no pueden colisionar",
                    codigo);
        }
        this.reglas = List.copyOf(reglas);
    }

    /**
     * Contadores que hará falta resolver antes de evaluar esta transacción.
     *
     * <p>Se pide al motor y no a cada regla para que el caso de uso haga una sola pasada de E/S.
     * El conjunto está deduplicado: dos reglas que miran el mismo contador provocan una única
     * consulta.
     */
    public Set<ClaveDeVelocidad> contadoresNecesarios(Transaccion transaccion) {
        Invariantes.noNulo(transaccion, "transaccion");
        Set<ClaveDeVelocidad> claves = new LinkedHashSet<>();
        for (ReglaDeFraude regla : reglas) {
            try {
                claves.addAll(regla.contadoresQueNecesita(transaccion));
            } catch (RuntimeException e) {
                // Una regla que no sabe ni declarar lo que necesita no debe impedir que las
                // demás obtengan sus contadores. Su fallo reaparecerá al evaluarla.
            }
        }
        return Set.copyOf(claves);
    }

    /** Ejecuta todas las reglas y agrega la evidencia y las advertencias. */
    public ResultadoDelMotor ejecutar(ContextoDeEvaluacion contexto) {
        Invariantes.noNulo(contexto, "contexto");

        List<SenalDeRiesgo> senales = new ArrayList<>();
        List<String> advertencias = new ArrayList<>();

        for (ReglaDeFraude regla : reglas) {
            ResultadoDeRegla resultado;
            try {
                resultado = regla.evaluar(contexto);
                if (resultado == null) {
                    resultado = new ResultadoDeRegla.Fallida("la regla devolvió null");
                }
            } catch (RuntimeException e) {
                resultado = new ResultadoDeRegla.Fallida(descripcionDe(e));
            }

            // Switch exhaustivo sobre la jerarquía sellada: si mañana aparece un desenlace
            // nuevo, esto deja de compilar en vez de ignorarlo en silencio.
            switch (resultado) {
                case ResultadoDeRegla.Disparada(SenalDeRiesgo senal) -> senales.add(senal);
                case ResultadoDeRegla.NoDisparada _ -> {
                    // La transacción pasó este control. No hay nada que registrar.
                }
                case ResultadoDeRegla.NoAplicable _ -> {
                    // La regla no era pertinente. Tampoco es una anomalía.
                }
                case ResultadoDeRegla.Degradada(String motivo) ->
                    advertencias.add("%s: sin datos suficientes (%s)".formatted(regla.codigo(), motivo));
                case ResultadoDeRegla.Fallida(String motivo) ->
                    advertencias.add("%s: error al evaluar (%s)".formatted(regla.codigo(), motivo));
            }
        }

        return new ResultadoDelMotor(senales, advertencias, reglas.size());
    }

    /** Códigos del catálogo, para métricas y para el endpoint de diagnóstico. */
    public List<String> codigos() {
        return reglas.stream().map(ReglaDeFraude::codigo).toList();
    }

    public int cantidadDeReglas() {
        return reglas.size();
    }

    private static String descripcionDe(RuntimeException e) {
        String mensaje = e.getMessage();
        return mensaje == null || mensaje.isBlank()
                ? e.getClass().getSimpleName()
                : "%s: %s".formatted(e.getClass().getSimpleName(), mensaje);
    }
}
