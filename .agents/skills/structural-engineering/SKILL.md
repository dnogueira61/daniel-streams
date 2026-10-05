---
name: structural-engineering
description: >-
  Metodologia de engenharia rigorosa para intervenções estruturais e arquiteturais no DanielStreams (adaptada de diagnosing-bugs, to-spec e git-guardrails de Matt Pocock).
  Ativar APENAS em alterações estruturais, novos subsistemas, redesenho de arquitetura ou refatoração de múltiplos módulos. NUNCA ativar em pequenas alterações, ajustes de UI, labels de texto ou correções pontuais de rotina.
---

# Diretrizes para Alterações Estruturais (DanielStreams)

> [!IMPORTANT]
> **Condição de Ativação:**
> Esta skill destina-se **exclusivamente** a intervenções de fundo (ex.: criação de rotinas automatizadas no GitHub Actions, refatoração do motor ExoPlayer/WebView, alterações na persistência e sincronização de dados).
> **NÃO aplicar em pequenos updates**, ajustes de texto, ordenação de botões, ou pequenos fixes visuais para manter agilidade máxima no desenvolvimento do dia a dia.

---

## 1. Especificação e Fatiamento Modular (`to-spec` / Vertical Slices)
Quando uma funcionalidade estrutural for solicitada:
1. **Contrato de Dados Primeiro:** Definir as estruturas de dados (classes Kotlin / schemas JSON) antes de alterar múltiplos componentes.
2. **Fatias Verticais Mínimas:** Dividir a implementação em passos atómicos que possam ser testados e validados independentemente.
3. **Isolamento de Risco:** Não misturar alterações cosméticas ou secundárias com grandes alterações estruturais.

---

## 2. Diagnóstico Sistemático de Problemas (`diagnosing-bugs`)
Para falhas estruturais de rede, streams ou reprodução:
1. **Isolar o Domínio do Problema:**
   - Falha de rede / stream: verificar se a origem é DNS, bloqueio geográfico/CORS, token expirado ou domínio alterado.
   - Falha na app: inspecionar exceções no logcat, ciclo de vida da Activity ou estado Compose.
2. **Formular Hipótese Testável:** Validar a causa raiz diretamente com um teste rápido ou inspeção de rede antes de propor alterações.
3. **Correção Cirúrgica:** Aplicar a correção mínima necessária sem desestabilizar os outros subsistemas.

---

## 3. Salvaguardas de Git e Releases (`git-guardrails`)
Antes de publicar qualquer versão estrutural:
1. **Compilação Obrigatória:** Executar `assembleRelease` e garantir `BUILD SUCCESSFUL` com 0 erros.
2. **Higiene de Repositório:** Verificar `git status` e `git diff` para garantir que ficheiros temporários (scripts de teste, APKs locais) não são adicionados ao repositório git.
3. **Rastreabilidade:** Criar commit com mensagem descritiva, tag semântica no formato `vX.XX` e publicar a release no GitHub com o respetivo APK compilado.
