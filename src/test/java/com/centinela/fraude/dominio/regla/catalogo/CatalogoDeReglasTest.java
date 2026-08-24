package com.centinela.fraude.dominio.regla.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.centinela.fraude.dominio.ObjetosDePrueba;
import com.centinela.fraude.dominio.PerfilDePrueba;
import com.centinela.fraude.dominio.modelo.SenalDeRiesgo;
import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.Canal;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import com.centinela.fraude.dominio.regla.MotorDeReglas;
import com.centinela.fraude.dominio.regla.ReglaDeFraude;
import com.centinela.fraude.dominio.regla.ResultadoDelMotor;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El catálogo completo sobre transacciones reales. Sin mocks y sin E/S: son nueve funciones
 * puras encadenadas, así que la suite entera corre en microsegundos y se puede ejecutar en cada
 * guardado.
 */
@DisplayName("Catálogo completo de reglas")
class CatalogoDeReglasTest {

    private final MotorDeReglas motor = new MotorDeReglas(CatalogoDeReglas.estandar());

    @Test
    @DisplayName("el catálogo se puede montar: no hay códigos duplicados")
    void elCatalogoEsConsistente() {
        assertThat(motor.cantidadDeReglas()).isEqualTo(9);
        assertThat(motor.codigos()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("una transacción normal de un cliente conocido no dispara nada")
    void transaccionLimpia() {
        // Con los contadores resueltos y por debajo del umbral, la operación habitual de un
        // cliente conocido debe salir limpia. Si este test falla, alguna regla dispara sobre el
        // caso base y estaríamos generando fricción sobre tráfico bueno.
        Map<ClaveDeVelocidad, Long> contadores = Map.of(
                ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente()), 2L,
                ClaveDeVelocidad.de(
                        DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, ObjetosDePrueba.DISPOSITIVO_CONOCIDO),
                1L);

        ResultadoDelMotor resultado = motor.ejecutar(ObjetosDePrueba.contexto(
                ObjetosDePrueba.transaccionWeb(), PerfilDePrueba.unPerfil().construir(), contadores));

        assertThat(resultado.senales())
                .as("señales inesperadas sobre tráfico legítimo: %s", resultado.senales())
                .isEmpty();
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("un escenario de toma de cuenta acumula varias señales y acaba bloqueado")
    void escenarioDeTomaDeCuenta() {
        // Dispositivo nuevo, desde Tokio una hora después de operar en Madrid, por 30 veces el
        // gasto habitual. Ninguna señal aislada rechaza; juntas sí.
        Transaccion sospechosa = ObjetosDePrueba.transaccion(
                ObjetosDePrueba.euros("3000.00"),
                ObjetosDePrueba.tarjetaNoPresente(),
                ObjetosDePrueba.comercioDeAltoRiesgo(),
                Canal.WEB,
                ObjetosDePrueba.tokio(),
                ObjetosDePrueba.DISPOSITIVO_NUEVO);

        var perfil = PerfilDePrueba.unPerfil()
                .conMedia("100.00")
                .vistoEn(ObjetosDePrueba.madrid(), ObjetosDePrueba.AHORA.minusSeconds(3600))
                .construir();

        Map<ClaveDeVelocidad, Long> contadores = Map.of(
                ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente()), 1L,
                ClaveDeVelocidad.de(DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, ObjetosDePrueba.DISPOSITIVO_NUEVO),
                1L);

        ResultadoDelMotor resultado =
                motor.ejecutar(ObjetosDePrueba.contexto(sospechosa, perfil, contadores));

        assertThat(resultado.senales())
                .extracting(SenalDeRiesgo::codigo)
                .contains(
                        ReglaViajeImposible.CODIGO,
                        ReglaDispositivoDesconocido.CODIGO,
                        ReglaPaisInusual.CODIGO,
                        ReglaDesviacionDeImporte.CODIGO,
                        ReglaComercioDeAltoRiesgo.CODIGO);
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("sin contadores, solo se degradan las reglas de ráfaga; las demás siguen decidiendo")
    void degradacionParcialSinContadores() {
        // Simula la caché de velocidad caída. Es el escenario que separa un motor honesto de
        // uno que aprueba a ciegas: las reglas de ráfaga avisan, el resto sigue trabajando.
        Transaccion desdeTokio = ObjetosDePrueba.transaccionDesde(ObjetosDePrueba.tokio());
        var perfil = PerfilDePrueba.unPerfil()
                .vistoEn(ObjetosDePrueba.madrid(), ObjetosDePrueba.AHORA.minusSeconds(3600))
                .construir();

        ResultadoDelMotor resultado = motor.ejecutar(ObjetosDePrueba.contexto(desdeTokio, perfil, Map.of()));

        assertThat(resultado.estaDegradado()).isTrue();
        assertThat(resultado.advertencias())
                .hasSize(2)
                .allSatisfy(advertencia -> assertThat(advertencia).contains("RAFAGA"));
        assertThat(resultado.senales()).extracting(SenalDeRiesgo::codigo).contains(ReglaViajeImposible.CODIGO);
    }

    @Test
    @DisplayName("un cliente nuevo se degrada en vez de acumular riesgo por no tener historial")
    void clienteNuevoNoEsSospechoso() {
        Map<ClaveDeVelocidad, Long> contadores = Map.of(
                ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente()), 1L,
                ClaveDeVelocidad.de(
                        DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, ObjetosDePrueba.DISPOSITIVO_CONOCIDO),
                1L);

        ResultadoDelMotor resultado = motor.ejecutar(
                ObjetosDePrueba.contexto(ObjetosDePrueba.transaccionWeb(), PerfilDePrueba.recienCreado(), contadores));

        // Ninguna señal: las reglas que dependen del historial se declaran degradadas en vez de
        // acusar. Es la diferencia entre "no sé" y "sospechoso", y confundirlas convertiría a
        // cada cliente nuevo en un falso positivo.
        assertThat(resultado.senales()).isEmpty();
        assertThat(resultado.advertencias()).isNotEmpty();
    }

    @Test
    @DisplayName("el motor pide exactamente los contadores que declaran sus reglas")
    void pideLosContadoresDeclarados() {
        assertThat(motor.contadoresNecesarios(ObjetosDePrueba.transaccionWeb()))
                .containsExactlyInAnyOrder(
                        ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, ObjetosDePrueba.cliente()),
                        ClaveDeVelocidad.de(
                                DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, ObjetosDePrueba.DISPOSITIVO_CONOCIDO));
    }

    @Test
    @DisplayName("todas las reglas del catálogo llevan un código en el formato acordado")
    void losCodigosSiguenLaConvencion() {
        for (ReglaDeFraude regla : CatalogoDeReglas.estandar()) {
            assertThat(regla.codigo())
                    .as("código de %s", regla.getClass().getSimpleName())
                    .matches("[A-Z][A-Z0-9_]*");
        }
    }
}
