package com.centinela.fraude.aplicacion.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.modelo.EvaluacionDeFraude;
import com.centinela.fraude.dominio.modelo.vo.IdTransaccion;
import com.centinela.fraude.dominio.puerto.salida.BandejaDeSalida;
import com.centinela.fraude.dominio.puerto.salida.PublicadorDeDecisiones;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RelevoDeBandejaDeSalida")
class RelevoDeBandejaDeSalidaTest {

    @Mock
    private BandejaDeSalida bandeja;

    @Mock
    private PublicadorDeDecisiones publicador;

    private RelevoDeBandejaDeSalida relevo() {
        return new RelevoDeBandejaDeSalida(bandeja, publicador, 50, 5);
    }

    private static EvaluacionDeFraude decision(String id) {
        return EvaluacionDeFraude.apartirDe(
                IdTransaccion.de(id), List.of(), ObjetosDePrueba.AHORA, Duration.ofMillis(3));
    }

    @Test
    @DisplayName("publica cada pendiente y la marca como publicada")
    void publicaYMarca() {
        EvaluacionDeFraude una = decision("tx-1");
        EvaluacionDeFraude otra = decision("tx-2");
        when(bandeja.pendientes(50))
                .thenReturn(List.of(new BandejaDeSalida.Pendiente(una, 0), new BandejaDeSalida.Pendiente(otra, 0)));

        var resultado = relevo().ejecutarCiclo();

        verify(publicador).publicar(una);
        verify(publicador).publicar(otra);
        verify(bandeja).marcarPublicada(una);
        verify(bandeja).marcarPublicada(otra);
        assertThat(resultado.publicadas()).isEqualTo(2);
        assertThat(resultado.fallidas()).isZero();
    }

    @Test
    @DisplayName("no marca como publicada una decisión cuyo envío falló")
    void noMarcaLoQueNoSalio() {
        // Es la garantía que sostiene el patrón: mientras el broker no acuse recibo, la entrada
        // sigue pendiente y el siguiente ciclo la reintenta. Marcarla antes perdería la
        // decisión exactamente igual que el diseño anterior.
        EvaluacionDeFraude una = decision("tx-1");
        when(bandeja.pendientes(50)).thenReturn(List.of(new BandejaDeSalida.Pendiente(una, 0)));
        doThrow(new IllegalStateException("broker no disponible")).when(publicador).publicar(any());

        var resultado = relevo().ejecutarCiclo();

        verify(bandeja, never()).marcarPublicada(any());
        verify(bandeja).registrarIntentoFallido(eq(una), org.mockito.ArgumentMatchers.contains("broker no disponible"));
        assertThat(resultado.fallidas()).isEqualTo(1);
    }

    @Test
    @DisplayName("una decisión problemática no bloquea al resto del lote")
    void unFalloNoDetieneElLote() {
        // Sin esto, una sola entrada envenenada pararía la cola entera, que es el fallo que
        // este patrón existe para evitar.
        EvaluacionDeFraude mala = decision("tx-mala");
        EvaluacionDeFraude buena = decision("tx-buena");
        when(bandeja.pendientes(50))
                .thenReturn(List.of(new BandejaDeSalida.Pendiente(mala, 0), new BandejaDeSalida.Pendiente(buena, 0)));
        doThrow(new IllegalStateException("mensaje demasiado grande"))
                .when(publicador)
                .publicar(mala);

        var resultado = relevo().ejecutarCiclo();

        verify(bandeja).marcarPublicada(buena);
        assertThat(resultado.publicadas()).isEqualTo(1);
        assertThat(resultado.fallidas()).isEqualTo(1);
    }

    @Test
    @DisplayName("un ciclo sin pendientes no hace nada ni se queja")
    void cicloVacio() {
        when(bandeja.pendientes(50)).thenReturn(List.of());

        var resultado = relevo().ejecutarCiclo();

        assertThat(resultado.huboTrabajo()).isFalse();
        verify(publicador, never()).publicar(any());
    }

    @Test
    @DisplayName("si la bandeja no se puede leer, el ciclo termina sin propagar")
    void bandejaIlegible() {
        // El relevo corre en un planificador: una excepción que se escape deja de programarse
        // en algunas configuraciones, y el outbox se quedaría mudo sin que nadie lo note.
        when(bandeja.pendientes(50)).thenThrow(new IllegalStateException("DynamoDB no responde"));

        var resultado = relevo().ejecutarCiclo();

        assertThat(resultado.huboTrabajo()).isFalse();
        verify(publicador, never()).publicar(any());
    }

    @Test
    @DisplayName("si tampoco se puede anotar el fallo, el ciclo sigue con las demás")
    void falloAlAnotarElFallo() {
        EvaluacionDeFraude mala = decision("tx-mala");
        EvaluacionDeFraude buena = decision("tx-buena");
        when(bandeja.pendientes(50))
                .thenReturn(List.of(new BandejaDeSalida.Pendiente(mala, 0), new BandejaDeSalida.Pendiente(buena, 0)));
        doThrow(new IllegalStateException("broker caído")).when(publicador).publicar(mala);
        doThrow(new IllegalStateException("tabla no disponible"))
                .when(bandeja)
                .registrarIntentoFallido(any(), any());

        var resultado = relevo().ejecutarCiclo();

        assertThat(resultado.publicadas()).isEqualTo(1);
        verify(bandeja).marcarPublicada(buena);
    }

    private static EvaluacionDeFraude eq(EvaluacionDeFraude valor) {
        return org.mockito.ArgumentMatchers.eq(valor);
    }
}
