package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.Set;

/**
 * Retrato de la normalidad de un cliente: contra qué se compara una transacción para decidir si
 * es rara.
 *
 * <p>Es el estado que el motor lee de la capa de persistencia caliente en cada evaluación, y por
 * eso está pensado para ser pequeño y de lectura por clave única. Todo lo que no quepa en una
 * lectura de milisegundos no pertenece aquí.
 *
 * <p>Un cliente sin historial no es un error: {@link #nuevoPara} devuelve un perfil vacío y
 * {@link #esFiable()} avisa de que las señales basadas en desviación todavía no significan nada.
 * Tratar "sin perfil" como excepción sería el error clásico que penaliza a todo cliente nuevo.
 *
 * @param transaccionesObservadas volumen sobre el que se calcularon los agregados
 * @param ultimaPosicion última geolocalización conocida, base de la regla de viaje imposible
 */
public record PerfilDeComportamiento(
        IdCliente cliente,
        Dinero importeMedio,
        Dinero importeMaximoHistorico,
        Set<String> paisesHabituales,
        Set<String> comerciosHabituales,
        Set<IdDispositivo> dispositivosConocidos,
        long transaccionesObservadas,
        Optional<Instant> ultimaActividad,
        Optional<Geolocalizacion> ultimaPosicion,
        Instant actualizadoEn) {

    /** Por debajo de este volumen los agregados tienen demasiada varianza para decidir sobre ellos. */
    public static final long MINIMO_PARA_SER_FIABLE = 30;

    public PerfilDeComportamiento {
        Invariantes.noNulo(cliente, "perfil.cliente");
        Invariantes.noNulo(importeMedio, "perfil.importeMedio");
        Invariantes.noNulo(importeMaximoHistorico, "perfil.importeMaximoHistorico");
        Invariantes.noNulo(actualizadoEn, "perfil.actualizadoEn");
        Invariantes.exigir(
                transaccionesObservadas >= 0, "perfil.transaccionesObservadas no puede ser negativo: %d", transaccionesObservadas);

        paisesHabituales = Set.copyOf(Invariantes.noNulo(paisesHabituales, "perfil.paisesHabituales"));
        comerciosHabituales = Set.copyOf(Invariantes.noNulo(comerciosHabituales, "perfil.comerciosHabituales"));
        dispositivosConocidos = Set.copyOf(Invariantes.noNulo(dispositivosConocidos, "perfil.dispositivosConocidos"));

        ultimaActividad = ultimaActividad == null ? Optional.empty() : ultimaActividad;
        ultimaPosicion = ultimaPosicion == null ? Optional.empty() : ultimaPosicion;
    }

    /** Perfil vacío para un cliente del que todavía no se sabe nada. */
    public static PerfilDeComportamiento nuevoPara(IdCliente cliente, Currency moneda, Instant ahora) {
        Invariantes.noNulo(moneda, "moneda de referencia");
        return new PerfilDeComportamiento(
                cliente,
                Dinero.cero(moneda),
                Dinero.cero(moneda),
                Set.of(),
                Set.of(),
                Set.of(),
                0L,
                Optional.empty(),
                Optional.empty(),
                Invariantes.noNulo(ahora, "ahora"));
    }

    /** Hay historial suficiente para que las señales de desviación tengan valor predictivo. */
    public boolean esFiable() {
        return transaccionesObservadas >= MINIMO_PARA_SER_FIABLE;
    }

    public boolean esPaisHabitual(String paisIso2) {
        return paisIso2 != null && paisesHabituales.contains(paisIso2.toUpperCase(java.util.Locale.ROOT));
    }

    public boolean esComercioHabitual(Comercio comercio) {
        return comercio != null && comerciosHabituales.contains(comercio.id());
    }

    public boolean conoceElDispositivo(IdDispositivo dispositivo) {
        return dispositivo != null && dispositivosConocidos.contains(dispositivo);
    }

    /**
     * Cuántas veces el importe supera al gasto medio del cliente.
     *
     * @return 0 si el perfil todavía no tiene media (cliente nuevo), de modo que la señal de
     *     desviación no se dispare por falta de datos
     */
    public BigDecimal desviacionSobreLaMedia(Dinero importe) {
        Invariantes.noNulo(importe, "importe");
        return importeMedio.esCero() ? BigDecimal.ZERO : importe.razonRespectoA(importeMedio);
    }

    /** El importe supera cualquier operación previa registrada del cliente. */
    public boolean superaElHistorico(Dinero importe) {
        Invariantes.noNulo(importe, "importe");
        return !importeMaximoHistorico.esCero() && importe.esMayorQue(importeMaximoHistorico);
    }

    /**
     * La transacción implica un desplazamiento físicamente imposible desde la última posición
     * conocida del cliente.
     *
     * @return false si falta cualquiera de los dos extremos: sin datos no se acusa
     */
    public boolean implicaViajeImposible(Transaccion transaccion) {
        Invariantes.noNulo(transaccion, "transaccion");
        return ultimaPosicion
                .flatMap(anterior -> ultimaActividad.map(momento -> new PosicionAnterior(anterior, momento)))
                .flatMap(anterior -> transaccion.origen().map(actual -> actual.implicaViajeImposibleDesde(
                        anterior.posicion(), Duration.between(anterior.momento(), transaccion.ocurridaEn()))))
                .orElse(false);
    }

    /** Par auxiliar para no anidar dos {@code Optional} en la comprobación de viaje imposible. */
    private record PosicionAnterior(Geolocalizacion posicion, Instant momento) {}

    @Override
    public String toString() {
        return "Perfil[cliente=%s, n=%d, medio=%s, fiable=%s]"
                .formatted(cliente.enmascarado(), transaccionesObservadas, importeMedio, esFiable());
    }
}
