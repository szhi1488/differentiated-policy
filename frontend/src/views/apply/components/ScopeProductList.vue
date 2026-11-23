<script setup>
import { computed } from 'vue'

const props = defineProps({
  products: { type: Array, required: true },
  label: { type: String, default: '命中颜色' },
})
const colors = computed(() => [...new Set(props.products.map((p) => p.colorName || '颜色未记录'))])
</script>

<template>
  <details class="scope-products">
    <summary>
      {{ label }} · {{ colors.slice(0, 3).join('、') }}{{ colors.length > 3 ? '等' : '' }} （{{
        products.length
      }}
      件商品）
    </summary>
    <ul>
      <li v-for="product in products" :key="product.goodsId">
        {{ product.goodsName || product.colorName || '颜色未记录' }} · 商品 ID {{ product.goodsId }}
      </li>
    </ul>
  </details>
</template>

<style scoped>
.scope-products {
  color: var(--policy-muted);
  font-size: 13px;
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.scope-products summary {
  color: var(--el-color-primary);
  cursor: pointer;
}
.scope-products ul {
  margin: 4px 0;
  padding-inline-start: 20px;
}
</style>
