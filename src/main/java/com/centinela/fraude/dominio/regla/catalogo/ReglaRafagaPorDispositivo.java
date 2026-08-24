package com.centinela.fraude.dominio.regla.catalogo;

import com.centinela.fraude.dominio.modelo.Transaccion;
import com.centinela.fraude.dominio.modelo.vo.ClaveDeVelocidad;
import com.centinela.fraude.dominio.modelo.vo.DimensionDeVelocidad;
import com.centinela.fraude.dominio.regla.PoliticaDeRiesgo;
import java.util.Optional;

/**
 * Demasiados intentos desde el mismo dispositivo en la ventana.
 *
 * <p>Es la firma del <em>card testing</em>: un lote de tarjetas robadas se prueba con cargos
 * pequeños desde una misma máquina para separar las que siguen vivas. Cada intento aislado
 * parece inofensivo —importe bajo, comercio normal—; solo el conteo lo delata, y por eso esta
 * regla no puede sustituirse por ninguna que mire una transacción sola.
 */
public final class ReglaRafagaPorDispositivo extends ReglaDeRafaga {

    public static final String CODIGO = "RAFAGA_POR_DISPOSITIVO";
    private static final int APORTE_POR_DEFECTO = 260;

    public ReglaRafagaPorDispositivo() {
        super(APORTE_POR_DEFECTO);
    }

    @Override
    public String codigo() {
        return CODIGO;
    }

    @Override
    protected Optional<ClaveDeVelocidad> clavePara(Transaccion transaccion) {
        return transaccion
                .dispositivo()
                .map(dispositivo -> ClaveDeVelocidad.de(DimensionDeVelocidad.INTENTOS_POR_DISPOSITIVO, dispositivo));
    }

    @Override
    protected long umbralDe(PoliticaDeRiesgo politica) {
        return politica.maxIntentosPorDispositivoEnVentana();
    }

    @Override
    protected String queSeCuenta() {
        return "intentos desde el dispositivo";
    }

    @Override
    protected String motivoNoAplicable() {
        return "la transacción no reporta huella de dispositivo";
    }
}
