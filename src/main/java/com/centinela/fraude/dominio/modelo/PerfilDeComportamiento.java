package com.centinela.fraude.dominio.modelo;

import com.centinela.fraude.dominio.compartido.Invariantes;
import com.centinela.fraude.dominio.modelo.vo.Comercio;
import com.centinela.fraude.dominio.modelo.vo.Dinero;
import com.centinela.fraude.dominio.modelo.vo.Geolocalizacion;
import com.centinela.fraude.dominio.modelo.vo.IdCliente;
import com.centinela.fraude.dominio.modelo.vo.IdDispositivo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Currency;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.SequencedSet;

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
 * <h2>Los conjuntos están acotados y conservan el orden de llegada</h2>
 *
 * Sin cota, un cliente de años acabaría con miles de comercios en su perfil y el item superaría
 * el límite de tamaño de la fila; además, cada lectura en el camino caliente pagaría ese peso.
 * Al llegar al tope se descarta el elemento más antiguo, y para eso el orden de inserción tiene
 * que sobrevivir: por eso se guardan como {@link SequencedSet} y no con {@code Set.copyOf}, que
 * lo pierde. La igualdad del record sigue siendo la de un conjunto, independiente del orden.
 *
 * @param version contador de bloqueo optimista; ver {@link #aprendiendoDe}
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
        Instant actualizadoEn,
        long version) {

    /** Por debajo de este volumen los agregados tienen demasiada varianza para decidir sobre ellos. */
    public static final long MINIMO_PARA_SER_FIABLE = 30;

    /** Casi nadie opera desde más de un puñado de países; el tope es holgura, no límite real. */
    public static final int MAXIMO_PAISES = 25;

    public static final int MAXIMO_COMERCIOS = 200;

    public static final int MAXIMO_DISPOSITIVOS = 50;

    public PerfilDeComportamiento {
        Invariantes.noNulo(cliente, "perfil.cliente");
        Invariantes.noNulo(importeMedio, "perfil.importeMedio");
        Invariantes.noNulo(importeMaximoHistorico, "perfil.importeMaximoHistorico");
        Invariantes.noNulo(actualizadoEn, "perfil.actualizadoEn");
        Invariantes.exigir(
                transaccionesObservadas >= 0,
                "perfil.transaccionesObservadas no puede ser negativo: %d",
                transaccionesObservadas);
        Invariantes.exigir(version >= 0, "perfil.version no puede ser negativa: %d", version);

        // Los códigos de país se normalizan a mayúsculas aquí y no solo al consultarlos: si el
        // conjunto guardara 'es' y la consulta buscara 'ES', esPaisHabitual devolvería false
        // para el país de siempre del cliente y la regla de país inusual dispararía en cada
        // operación. La normalización tiene que estar en el mismo sitio en los dos lados.
        paisesHabituales = secuenciaInmutable(
                Invariantes.noNulo(paisesHabituales, "perfil.paisesHabituales").stream()
                        .map(pais -> Invariantes.paisIso2(pais, "perfil.paisesHabituales"))
                        .toList());
        comerciosHabituales =
                secuenciaInmutable(Invariantes.noNulo(comerciosHabituales, "perfil.comerciosHabituales"));
        dispositivosConocidos =
                secuenciaInmutable(Invariantes.noNulo(dispositivosConocidos, "perfil.dispositivosConocidos"));

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
                Invariantes.noNulo(ahora, "ahora"),
                0L);
    }

    // ---------------------------------------------------------------------------------------
    // Aprendizaje
    // ---------------------------------------------------------------------------------------

    /**
     * Devuelve el perfil resultante de incorporar esta transacción, o vacío si no hay nada que
     * aprender de ella.
     *
     * <h2>Sólo se aprende de lo que se aprobó sin fricción</h2>
     *
     * Si el motor dudó —rechazó, pidió autenticación o mandó a revisión— esa operación no
     * describe la normalidad del cliente. Incorporarla sería el fallo más grave que puede tener
     * un motor de fraude que aprende solo: bastaría con que un defraudador colara una compra
     * desde Tokio para que Japón pasara a ser país habitual y la siguiente ya no disparara. El
     * atacante entrenaría el modelo a su favor.
     *
     * <p>Limitación conocida: un {@code DESAFIAR} que el titular supera legítimamente tampoco
     * enseña nada, porque el resultado de la autenticación no vuelve a este motor. Cerrar ese
     * bucle exige que el autorizador publique el desenlace.
     *
     * <h2>Bloqueo optimista</h2>
     *
     * El perfil devuelto lleva {@code version + 1}. El repositorio escribe condicionando a que
     * la fila siga en la versión anterior; si no, otra evaluación del mismo cliente ganó la
     * carrera y esta actualización se descarta o se reintenta releyendo.
     *
     * @param momento instante de la evaluación, que pasa a ser la última actividad conocida
     */
    public Optional<PerfilDeComportamiento> aprendiendoDe(
            Transaccion transaccion, Decision decision, Instant momento) {

        Invariantes.noNulo(transaccion, "transaccion");
        Invariantes.noNulo(decision, "decision");
        Invariantes.noNulo(momento, "momento");
        Invariantes.exigir(
                transaccion.cliente().equals(cliente),
                "La transacción pertenece a otro cliente que el del perfil");

        if (decision.introduceFriccion() || decision == Decision.REVISAR) {
            return Optional.empty();
        }

        Dinero importe = transaccion.importe();
        boolean mismaMoneda = importe.moneda().equals(importeMedio.moneda());

        return Optional.of(new PerfilDeComportamiento(
                cliente,
                mismaMoneda ? mediaIncorporando(importe) : importeMedio,
                mismaMoneda && importe.esMayorQue(importeMaximoHistorico) ? importe : importeMaximoHistorico,
                transaccion
                        .origen()
                        .map(origen -> acotar(paisesHabituales, origen.paisIso2(), MAXIMO_PAISES))
                        .orElse(paisesHabituales),
                acotar(comerciosHabituales, transaccion.comercio().id(), MAXIMO_COMERCIOS),
                transaccion
                        .dispositivo()
                        .map(dispositivo -> acotar(dispositivosConocidos, dispositivo, MAXIMO_DISPOSITIVOS))
                        .orElse(dispositivosConocidos),
                transaccionesObservadas + 1,
                Optional.of(momento),
                transaccion.origen().or(() -> ultimaPosicion),
                momento,
                version + 1));
    }

    /**
     * Media móvil exacta: {@code (media * n + importe) / (n + 1)}.
     *
     * <p>Se recalcula desde el total acumulado en vez de guardar la suma, para que el perfil siga
     * siendo pequeño. El precio es un redondeo por operación a los decimales de la moneda, que
     * introduce una deriva mínima; es aceptable porque esta media describe un comportamiento, no
     * cuadra un saldo.
     */
    private Dinero mediaIncorporando(Dinero importe) {
        BigDecimal acumulado = importeMedio
                .importe()
                .multiply(BigDecimal.valueOf(transaccionesObservadas))
                .add(importe.importe());
        BigDecimal media = acumulado.divide(BigDecimal.valueOf(transaccionesObservadas + 1), 6, RoundingMode.HALF_EVEN);
        return Dinero.de(media, importeMedio.moneda());
    }

    /**
     * Añade el elemento conservando el orden de llegada y descartando los más antiguos si se
     * supera el tope. Si ya estaba, no se reordena: mover el elemento al final convertiría esto
     * en una LRU, y para eso haría falta guardar recencia por elemento.
     */
    private static <T> Set<T> acotar(Set<T> actual, T nuevo, int maximo) {
        if (actual.contains(nuevo)) {
            return actual;
        }
        LinkedHashSet<T> resultado = new LinkedHashSet<>(actual);
        resultado.add(nuevo);
        Iterator<T> desdeElMasAntiguo = resultado.iterator();
        while (resultado.size() > maximo) {
            desdeElMasAntiguo.next();
            desdeElMasAntiguo.remove();
        }
        return resultado;
    }

    private static <T> Set<T> secuenciaInmutable(Collection<T> valores) {
        return java.util.Collections.unmodifiableSequencedSet(new LinkedHashSet<>(valores));
    }

    // ---------------------------------------------------------------------------------------
    // Consultas
    // ---------------------------------------------------------------------------------------

    /** Hay historial suficiente para que las señales de desviación tengan valor predictivo. */
    public boolean esFiable() {
        return transaccionesObservadas >= MINIMO_PARA_SER_FIABLE;
    }

    public boolean esPaisHabitual(String paisIso2) {
        return paisIso2 != null && paisesHabituales.contains(paisIso2.toUpperCase(Locale.ROOT));
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
        return "Perfil[cliente=%s, v=%d, n=%d, medio=%s, fiable=%s]"
                .formatted(cliente.enmascarado(), version, transaccionesObservadas, importeMedio, esFiable());
    }
}
