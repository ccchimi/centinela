package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.PuntajeDeRiesgo;
import com.centinela.fraude.dominio.regla.ContextoDeEvaluacion;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDeRegla;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Base de las reglas de ráfaga: demasiados eventos de una dimensión en la ventana reciente.
 *
 * <p>Sellada porque el mecanismo de conteo y escalado es común, pero el conjunto de ejes que se
 * cuentan es cerrado y deliberado: cada dimensión nueva es una clave más en la caché caliente y
 * un coste que hay que decidir, no heredar por accidente.
 *
 * <p>Lo que aporta la clase base y las subclases no repiten: declarar el contador antes de
 * evaluar, distinguir "contador no disponible" de "contador en cero", y escalar el aporte con
 * el exceso sobre el umbral.
 */
sealed abstract class ReglaDeRafaga implements ReglaDeFraude
        permits ReglaRafagaPorCliente, ReglaRafagaPorDispositivo {

    /** Techo del escalado: por muy grande que sea la ráfaga, el aporte no pasa del triple. */
    private static final double FACTOR_MAXIMO = 3.0;

    private final int aportePorDefecto;

    protected ReglaDeRafaga(int aportePorDefecto) {
        this.aportePorDefecto = aportePorDefecto;
    }

    /** Clave del contador para esta transacción, o vacío si la regla no es pertinente. */
    protected abstract Optional<ClaveDeVelocidad> clavePara(Transaccion transaccion);

    /** Umbral configurado en la política para esta ráfaga. */
    protected abstract long umbralDe(PoliticaDeRiesgo politica);

    /** Qué se estaba contando, para el texto de la señal. */
    protected abstract String queSeCuenta();

    /** Motivo cuando la regla no aplica. Las ráfagas que siempre aplican no lo sobrescriben. */
    protected String motivoNoAplicable() {
        return "la transacción no aporta el identificador que esta ráfaga cuenta";
    }

    @Override
    public final Set<ClaveDeVelocidad> contadoresQueNecesita(Transaccion transaccion) {
        return clavePara(transaccion).map(Set::of).orElseGet(Set::of);
    }

    @Override
    public final ResultadoDeRegla evaluar(ContextoDeEvaluacion contexto) {
        Optional<ClaveDeVelocidad> clave = clavePara(contexto.transaccion());
        if (clave.isEmpty()) {
            return ResultadoDeRegla.noAplicable(motivoNoAplicable());
        }

        OptionalLong observados = contexto.contador(clave.orElseThrow());
        if (observados.isEmpty()) {
            // Crítico: no es lo mismo que "cero eventos". Si la caché está caída y esto se
            // tratara como no disparada, el motor aprobaría ráfagas creyéndose sano.
            return ResultadoDeRegla.degradada("el contador de velocidad no estuvo disponible");
        }

        long cuenta = observados.getAsLong();
        long umbral = umbralDe(contexto.politica());
        if (cuenta <= umbral) {
            return ResultadoDeRegla.noDisparada();
        }

        int aporte = escalar(cuenta, umbral, contexto.politica().aporteDe(codigo(), aportePorDefecto));

        return ResultadoDeRegla.disparada(
                codigo(),
                "%d %s en los últimos %d s, umbral %d"
                        .formatted(cuenta, queSeCuenta(), contexto.politica().ventanaDeVelocidad().toSeconds(), umbral),
                aporte);
    }

    /**
     * El aporte crece con el exceso sobre el umbral y satura en el triple del peso base.
     *
     * <p>Rebasar el umbral por uno no es lo mismo que multiplicarlo por diez, y un escalón
     * único haría que ambas cosas puntuaran igual. El techo evita que una ráfaga extrema
     * sature el puntaje ella sola y anule el resto de la evidencia.
     */
    private static int escalar(long cuenta, long umbral, int aporteBase) {
        double factor = Math.min(FACTOR_MAXIMO, 1.0 + (double) (cuenta - umbral) / umbral);
        return (int) Math.min(PuntajeDeRiesgo.MAXIMO, Math.round(aporteBase * factor));
    }
}
