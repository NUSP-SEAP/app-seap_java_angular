package br.leg.senado.nusp.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fecha automaticamente sessões de operação (OPR_REGISTRO_AUDIO) que ficaram
 * em aberto de dias anteriores. Equivale ao management command
 * fechar_sessoes_abertas.py do Django.
 *
 * Executa diariamente às 06:00 BRT (09:00 UTC).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessaoSchedulerService {

    private final EntityManager em;

    @Scheduled(cron = "0 0 9 * * *") // 09:00 UTC = 06:00 BRT
    @Transactional
    public void fecharSessoesAbertas() {
        log.info("[fechar_sessoes] Iniciando fechamento automático de sessões abertas...");

        // Query 1 — Corrige entradas: copia hora_saida → horario_termino onde está NULL
        int corrigidas = em.createNativeQuery("""
                UPDATE OPR_REGISTRO_ENTRADA e SET
                    e.HORARIO_TERMINO = e.HORA_SAIDA
                WHERE e.REGISTRO_ID IN (
                    SELECT r.ID FROM OPR_REGISTRO_AUDIO r
                    WHERE r.EM_ABERTO = 1 AND r.DATA < TRUNC(SYSDATE)
                )
                AND e.HORARIO_TERMINO IS NULL
                AND e.HORA_SAIDA IS NOT NULL
                """).executeUpdate();

        // Query 2 — Fecha as sessões usando dados da última entrada (maior hora_saida)
        int fechadas = em.createNativeQuery("""
                UPDATE OPR_REGISTRO_AUDIO r SET
                    r.EM_ABERTO = 0,
                    r.FECHADO_EM = (
                        SELECT MAX(e.HORA_SAIDA) FROM OPR_REGISTRO_ENTRADA e
                        WHERE e.REGISTRO_ID = r.ID AND e.HORA_SAIDA IS NOT NULL
                    ),
                    r.FECHADO_POR = (
                        SELECT e2.OPERADOR_ID FROM OPR_REGISTRO_ENTRADA e2
                        WHERE e2.REGISTRO_ID = r.ID AND e2.HORA_SAIDA IS NOT NULL
                        ORDER BY e2.HORA_SAIDA DESC FETCH FIRST 1 ROW ONLY
                    )
                WHERE r.EM_ABERTO = 1
                AND r.DATA < TRUNC(SYSDATE)
                """).executeUpdate();

        log.info("[fechar_sessoes] Concluído: {} entradas corrigidas, {} sessões fechadas.", corrigidas, fechadas);
    }
}
