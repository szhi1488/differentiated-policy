<script setup>
import { computed } from 'vue'
const props = defineProps({ form: { type: Object, required: true } })
defineEmits(['update-time', 'update-field'])
const policyTime = computed(() =>
  props.form.startTime && props.form.endTime ? [props.form.startTime, props.form.endTime] : [],
)
</script>
<template>
  <section class="form-section" aria-labelledby="time-title">
    <h2 id="time-title">
      <span></span>
      二、政策时间与详情
    </h2>
    <div class="two-columns">
      <el-form-item label="开始结束时间（北京时间）" required>
        <el-date-picker
          :model-value="policyTime"
          @update:model-value="$emit('update-time', $event)"
          type="datetimerange"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          start-label="政策开始时间"
          end-label="政策结束时间"
          popper-class="policy-date-popper"
        />
      </el-form-item>
      <el-form-item label="特别备注">
        <el-input
          :model-value="form.remark"
          @update:model-value="$emit('update-field', 'remark', $event)"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          aria-label="特别备注"
          placeholder="请输入特别备注（选填）"
        />
      </el-form-item>
    </div>
  </section>
</template>
