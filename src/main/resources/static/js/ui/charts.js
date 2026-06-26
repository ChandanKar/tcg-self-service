/**
 * ECharts wrapper for dynamic panels.
 * Keeps chart setup small and handles hidden tab / slideout lifecycle.
 */
const VmCharts = (function() {
    'use strict';

    const registry = new Map();
    const palette = ['#2563eb', '#059669', '#d97706', '#dc2626', '#7c3aed', '#0891b2'];
    const textStyle = {
        fontFamily: 'Inter, Segoe UI, Arial, sans-serif',
        color: '#64748b'
    };

    function init() {
        $(window).off('resize.vmCharts').on('resize.vmCharts', resizeAll);
        $(document).off('shown.bs.tab.vmCharts').on('shown.bs.tab.vmCharts', function(e) {
            const target = e.target && e.target.getAttribute('data-bs-target');
            if (target) {
                setTimeout(() => initWithin(document.querySelector(target)), 30);
            }
            resizeAll();
        });
        $(document).off('slideout:opened.vmCharts').on('slideout:opened.vmCharts', function(_e, panel) {
            setTimeout(() => initWithin(panel || document), 40);
        });
    }

    function initWithin(root) {
        if (!window.echarts || !root) return;
        const scope = root instanceof Element ? root : document;
        const charts = scope.matches && scope.matches('.vm-echart')
            ? [scope]
            : Array.from(scope.querySelectorAll('.vm-echart'));

        charts.forEach(initOne);
    }

    function initOne(el) {
        if (!el || registry.has(el)) {
            if (registry.has(el)) registry.get(el).resize();
            return;
        }

        if (el.offsetWidth === 0 || el.offsetHeight === 0) return;

        const config = readConfig(el);
        if (!config) {
            el.innerHTML = '<span>No chart data</span>';
            return;
        }

        const chart = echarts.init(el, null, { renderer: 'svg' });
        chart.setOption(buildOption(config));
        registry.set(el, chart);
    }

    function readConfig(el) {
        const id = el.getAttribute('data-chart-config-id');
        if (!id) return null;
        const configEl = document.getElementById(id);
        if (!configEl) return null;
        try {
            return JSON.parse(configEl.textContent || '{}');
        } catch (e) {
            console.warn('Invalid chart config', id, e);
            return null;
        }
    }

    function buildOption(config) {
        const lines = normalizeSeries(config.series);
        const labels = buildLabels(lines);
        return {
            color: lines.map(line => line.color),
            animationDuration: 450,
            tooltip: {
                trigger: 'axis',
                confine: true,
                valueFormatter: value => formatValue(value, config.unit)
            },
            grid: {
                left: 6,
                right: 6,
                top: 8,
                bottom: 4,
                containLabel: false
            },
            xAxis: {
                type: 'category',
                boundaryGap: false,
                data: labels,
                axisLine: { show: false },
                axisTick: { show: false },
                axisLabel: { show: false }
            },
            yAxis: {
                type: 'value',
                axisLine: { show: false },
                axisTick: { show: false },
                axisLabel: { show: false },
                splitLine: { lineStyle: { color: '#eef2f7' } }
            },
            series: lines.map(line => ({
                name: line.name,
                type: 'line',
                smooth: true,
                showSymbol: false,
                symbolSize: 5,
                lineStyle: { width: lines.length > 1 ? 2 : 2.4 },
                areaStyle: lines.length === 1 ? { opacity: 0.12 } : undefined,
                emphasis: { focus: 'series' },
                data: line.values
            })),
            textStyle
        };
    }

    function normalizeSeries(series) {
        const input = Array.isArray(series) ? series : [];
        return input
            .filter(item => Array.isArray(item.values) && item.values.length > 1)
            .slice(0, palette.length)
            .map((item, index) => ({
                name: item.name || item.vmId || `Series ${index + 1}`,
                color: item.color || palette[index],
                values: item.values.map(value => Number(value) || 0)
            }));
    }

    function buildLabels(lines) {
        const length = Math.max(...lines.map(line => line.values.length), 0);
        return Array.from({ length }, (_item, index) => `${index + 1}`);
    }

    function formatValue(value, unit) {
        const numeric = Number(value);
        if (Number.isNaN(numeric)) return value;
        if (unit === 'percent') return `${Math.round(numeric)}%`;
        if (unit === 'mb') return `${numeric >= 10 ? numeric.toFixed(0) : numeric.toFixed(1)} MB`;
        return numeric;
    }

    function resizeAll() {
        registry.forEach(chart => chart.resize());
    }

    function disposeWithin(root) {
        if (!root) return;
        const scope = root instanceof Element ? root : document.querySelector(root);
        if (!scope) return;
        registry.forEach((chart, el) => {
            if (scope === el || scope.contains(el)) {
                chart.dispose();
                registry.delete(el);
            }
        });
    }

    return {
        init,
        initWithin,
        disposeWithin,
        resizeAll
    };
})();
