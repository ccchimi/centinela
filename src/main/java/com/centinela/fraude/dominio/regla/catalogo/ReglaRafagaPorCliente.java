package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import java.util.Optional;

/**
 * Demasiadas operaciones del mismo titular en la ventana.
 *
 * <p>Patrón típico tras una toma de control: el defraudador tiene minutos antes de que el
 * titular reaccione y vacía el saldo disponible en una cascada de cargos.
 */
public final class ReglaRafagaPorCliente extends ReglaDeRafaga {

    public static final String CODIGO = "RAFAGA_POR_CLIENTE";
    private static final int APORTE_POR_DEFECTO = 200;

    public ReglaRafagaPorCliente() {
        super(APORTE_POR_DEFECTO);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    protected Optional<ClaveDeVelocidad> clavePara(Transaccion transaccion) {
        // Siempre aplica: toda transacción tiene titular.
        return Optional.of(ClaveDeVelocidad.de(DimensionDeVelocidad.TRANSACCIONES_POR_CLIENTE, transaccion.cliente()));
    }

    @Override
    protected long umbralDe(PoliticaDeRiesgo politica) {
        return politica.maxTransaccionesPorClienteEnVentana();
    }

    @Override
    protected String queSeCuenta() {
        return "operaciones del titular";
    }
}
