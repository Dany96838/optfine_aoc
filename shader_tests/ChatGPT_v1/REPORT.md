# Relatório técnico — ChatGPT v1

## Base
Sildur Vibrant Shaders v2.02 Lite.zip

## Integridade
Original SHA-256:
16746ee127e4c0b07f7e62018589100845338e633eea6bed8cf8c09939aa9eea

Otimizado SHA-256:
0a4b1e73c66cb4033bbac031cf030cfa3fc70b3872828ed4f4bda7225759e1f0

## O que foi otimizado
- pow(x, vec3(1.0)) foi substituído pelo próprio x em iluminação.
- O quadrado do bloom foi transformado em multiplicação e o length(glow) passou a ser calculado uma única vez.
- No volumetric lighting, length(fragpos) e o mesmo termo exponencial eram calculados duas vezes; agora são calculados uma vez e reutilizados.
- No godrays, pow(align, vec2(2.0)) foi substituído por align*align.
- mix(A,B,1.0) foi simplificado para B onde era matematicamente idêntico.

## O que NÃO foi alterado intencionalmente
- algoritmo da água;
- normals da água;
- reflexos da água;
- resolução de texturas;
- qualidade geral por redução agressiva;
- gotículas de chuva/neve na tela;
- efeitos principais.

## Limitação
Não temos um runtime Minecraft 26.2 + GPU Mali-G720 neste ambiente. Portanto, a otimização foi verificada por inspeção e comparação do código/ZIP, mas o ganho de FPS precisa ser medido no Poco X7 Pro.

## Comparação com Claude
Quando o Claude terminar, coloque o resultado dele em outra pasta/branch. Depois podemos comparar:
1. arquivos modificados;
2. quantidade e tipo de operações removidas;
3. segurança visual;
4. compilação;
5. FPS no mesmo cenário;
6. estabilidade da água;
7. chuva/neve;
8. paisagem/árvores;
9. lava;
10. reflexos e godrays.
